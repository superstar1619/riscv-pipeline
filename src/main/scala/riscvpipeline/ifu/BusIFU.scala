package riscvpipeline.ifu

import chisel3._
import riscvpipeline.config.CpuConfig
import riscvpipeline.ebu.AHBInterface
import riscvpipeline.isa.Architecture._

/** 第九章同步本地/总线取指；PCF 为字节地址，D 指令与 PCD 在同一消费沿更新。
  * 总线请求独立于 StallF/D；只读 BusFSM 在 Mem3 等待 D 可接收，避免自停顿回路。
  * 已接受请求的重定向先排空旧 Data，再丢弃旧响应，重新请求目标地址。
  * 所有状态采用显式 clk、Bool 同步 reset；组合请求不与 reset 门控。
  */
final class BusIFU(val config: CpuConfig) extends RawModule {
  require(config.bus.nonEmpty, "BusIFU requires BusConfig")
  private val busConfig = config.bus.get
  private val localBytes = BigInt(config.imemDepth) * INSTRUCTION_BYTES
  private val localBase = config.resetVector / localBytes * localBytes
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new IFUIO(config))

  val PCF = withClockAndReset(clk, reset) { RegInit(config.resetVector.U(config.xlen.W)) }
  val PCD = withClockAndReset(clk, reset) { RegInit(0.U(config.xlen.W)) }
  val ValidD = withClockAndReset(clk, reset) { RegInit(false.B) }
  val FetchFaultD = withClockAndReset(clk, reset) { RegInit(false.B) }
  val DFromBus = withClockAndReset(clk, reset) { RegInit(true.B) }
  val BusInstrD = withClockAndReset(clk, reset) { RegInit(0.U(INSTRUCTION_WIDTH.W)) }
  val BusPending = withClockAndReset(clk, reset) { RegInit(false.B) }
  val BusKilled = withClockAndReset(clk, reset) { RegInit(false.B) }
  val BusPC = withClockAndReset(clk, reset) { RegInit(0.U(config.xlen.W)) }

  def inWindow(base: BigInt, bytes: BigInt): Bool =
    PCF.pad(35) >= base.U(35.W) && PCF.pad(35) < (base + bytes).U(35.W)

  val LocalF = busConfig.iromSupported.B && inWindow(localBase, localBytes)
  val BusMappedF = inWindow(busConfig.bootRomBase, busConfig.bootRomBytes) ||
    inWindow(busConfig.ramBase, busConfig.ramBytes)
  val AlignedF = PCF(INSTRUCTION_ALIGNMENT_BITS - 1, 0) === 0.U
  val BusFetchF = !LocalF && BusMappedF && AlignedF
  val FaultF = !AlignedF || (!LocalF && !BusMappedF)
  val Redirect = io.RedirectM || io.PCSrcE || io.FlushD
  val FetchCancel = io.BusFlushF || Redirect

  val bus = Module(new AHBInterface(true))
  bus.clk := clk
  bus.reset := reset
  bus.io.Stall := io.StallD
  bus.io.Flush := FetchCancel
  bus.io.BusRW := Mux(BusFetchF, 2.U(2.W), 0.U(2.W))
  bus.io.HREADY := io.ahb.HREADY
  bus.io.HRDATA := io.ahb.HRDATA
  bus.io.ByteMask := 0.U(4.W)
  bus.io.WriteData := 0.U(INSTRUCTION_WIDTH.W)
  // 停顿只由已寄存 Data 状态和原始 Adr 意图产生，不依赖实际 Redirect/FlushD。
  // 实际重定向仍可取消 HTRANS；M 候选取消由 BusFlushF 独立解除原始 Adr 等待。
  io.BusStallF := bus.io.BusCommitted || (!BusPending && BusFetchF && !io.BusFlushF)
  io.BusCommittedF := bus.io.BusCommitted
  io.ahb.HADDR := PCF.pad(34)
  io.ahb.HWDATA := bus.io.HWDATA
  io.ahb.HWSTRB := bus.io.HWSTRB
  io.ahb.HWRITE := bus.io.HWRITE
  io.ahb.HSIZE := 2.U(3.W)
  io.ahb.HBURST := 0.U(3.W)
  io.ahb.HPROT := 2.U(4.W)
  io.ahb.HTRANS := bus.io.HTRANS
  io.ahb.HMASTLOCK := false.B

  val AddressAccept = bus.io.HTRANS === 2.U(2.W) && io.ahb.HREADY
  val BusDone = BusPending && !bus.io.BusStall && !bus.io.BusCommitted
  val BusDrain = BusDone && !io.StallD
  val BusConsume = BusDrain && !BusKilled && !FetchCancel
  val LocalConsume = LocalF && AlignedF && !BusPending && !io.StallD && !FetchCancel
  val FaultConsume = FaultF && !BusPending && !io.StallD && !FetchCancel

  when(AddressAccept) {
    BusPending := true.B
    BusKilled := false.B
    BusPC := PCF
  }.elsewhen(BusDrain) {
    BusPending := false.B
    BusKilled := false.B
  }
  when(Redirect && BusPending && !BusDrain) { BusKilled := true.B }

  val LocalInstruction = WireDefault(0.U(INSTRUCTION_WIDTH.W))
  if (busConfig.iromSupported) {
    val irom = Module(new IROM(config))
    irom.clk := clk
    irom.io.a := PCF
    irom.io.en := LocalConsume
    LocalInstruction := irom.io.rd
  }

  when(io.RedirectM) {
    PCF := io.RedirectPCM
  }.elsewhen(io.PCSrcE) {
    PCF := io.IEUAdrE
  }.elsewhen(!io.StallF && (LocalConsume || BusConsume || FaultConsume)) {
    PCF := PCF + INSTRUCTION_BYTES.U(config.xlen.W)
  }
  when(Redirect) {
    ValidD := false.B
    FetchFaultD := false.B
  }.elsewhen(!io.StallD) {
    ValidD := LocalConsume || BusConsume || FaultConsume
    when(LocalConsume || BusConsume || FaultConsume) {
      PCD := Mux(BusConsume, BusPC, PCF)
      FetchFaultD := config.privilegedSupported.B && FaultConsume
      DFromBus := !LocalConsume
    }
    when(BusConsume) { BusInstrD := bus.io.FetchBuffer }
    when(FaultConsume) { BusInstrD := 0.U(INSTRUCTION_WIDTH.W) }
  }

  io.PCF := PCF
  io.PCD := PCD
  io.PCPlus4D := PCD + INSTRUCTION_BYTES.U(config.xlen.W)
  io.ValidD := ValidD
  io.InstrAccessFaultD := FetchFaultD
  io.InstrD := Mux(FetchFaultD, 0.U(INSTRUCTION_WIDTH.W),
    Mux(DFromBus, BusInstrD, LocalInstruction))
}
