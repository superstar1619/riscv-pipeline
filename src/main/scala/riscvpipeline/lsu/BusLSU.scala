package riscvpipeline.lsu

import chisel3._
import chisel3.util.MuxLookup
import riscvpipeline.config.CpuConfig
import riscvpipeline.control.ControlEncoding._
import riscvpipeline.ebu.AHBInterface
import riscvpipeline.isa.Architecture._
import riscvpipeline.isa.InstructionEncoding._
import riscvpipeline.privileged.EndianSwap

/** 第九章 LSU：M 级总线访存与可选 E 读/M 写 DTIM，显式同域同步复位。
  * BusRW 不依赖总 StallM 或 SuppressM，避免自停顿回路；BusFlushM 只取消未接受请求。
  * DTIM 响应到达时直接旁路，随后保存原生字，使其他总线停顿不丢同步读结果。
  */
final class BusLSU(val config: CpuConfig) extends RawModule {
  require(config.bus.nonEmpty, "BusLSU requires BusConfig")
  private val busConfig = config.bus.get
  private val localBytes = BigInt(config.dmemDepth) * BYTES_PER_WORD
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new LSUIO(config))

  def inWindow(address: UInt, base: BigInt, bytes: BigInt): Bool =
    address.pad(35) >= base.U(35.W) && address.pad(35) < (base + bytes).U(35.W)

  val LocalE = busConfig.dtimSupported.B && inWindow(io.IEUAdrE, 0, localBytes)
  val LocalM = busConfig.dtimSupported.B && inWindow(io.IEUAdrM, 0, localBytes)
  val RomM = inWindow(io.IEUAdrM, busConfig.bootRomBase, busConfig.bootRomBytes)
  val RamM = inWindow(io.IEUAdrM, busConfig.ramBase, busConfig.ramBytes)
  val APBM = inWindow(io.IEUAdrM, busConfig.apbBase, busConfig.apbBytes)
  val ReadM = io.MemRWM === MEM_READ.U(MEM_WIDTH.W)
  val WriteM = io.MemRWM === MEM_WRITE.U(MEM_WIDTH.W)
  val LegalReadE = Seq(LOAD_BYTE, LOAD_HALF, LOAD_WORD, LOAD_BYTE_U, LOAD_HALF_U)
    .map(code => io.Funct3E === code.U(FUNCT3_WIDTH.W)).reduce(_ || _)
  val LegalReadM = Seq(LOAD_BYTE, LOAD_HALF, LOAD_WORD, LOAD_BYTE_U, LOAD_HALF_U)
    .map(code => io.Funct3M === code.U(FUNCT3_WIDTH.W)).reduce(_ || _)
  val LegalWriteM = Seq(STORE_BYTE, STORE_HALF, STORE_WORD)
    .map(code => io.Funct3M === code.U(FUNCT3_WIDTH.W)).reduce(_ || _)
  val MisalignedE = (io.Funct3E(1, 0) === 1.U && io.IEUAdrE(0)) ||
    (io.Funct3E(1, 0) === 2.U && io.IEUAdrE(1, 0).orR)
  val MisalignedM = (io.Funct3M(1, 0) === 1.U && io.IEUAdrM(0)) ||
    (io.Funct3M(1, 0) === 2.U && io.IEUAdrM(1, 0).orR)
  val ReadMappedM = LocalM || RomM || RamM || APBM
  val WriteMappedM = LocalM || RamM || APBM
  io.LoadMisalignedFaultM := config.privilegedSupported.B && ReadM && MisalignedM
  io.StoreAmoMisalignedFaultM := config.privilegedSupported.B && WriteM && MisalignedM
  io.LoadAccessFaultM := config.privilegedSupported.B && ReadM && !ReadMappedM
  io.StoreAmoAccessFaultM := config.privilegedSupported.B && WriteM && !WriteMappedM
  val ReadAllowedM = ReadM && LegalReadM && !MisalignedM && ReadMappedM
  val WriteAllowedM = WriteM && LegalWriteM && !MisalignedM && WriteMappedM

  val subwordWrite = Module(new SubwordWrite(config.xlen))
  val swByteMask = Module(new SwByteMask(config.xlen))
  val writeSwap = Module(new EndianSwap)
  val readSwap = Module(new EndianSwap)
  val subwordRead = Module(new SubwordRead(config.xlen))
  val BigEndianM = config.privilegedSupported.B && io.BigEndianM
  subwordWrite.io.Funct3 := io.Funct3M
  subwordWrite.io.WriteData := io.WriteDataM
  swByteMask.io.Funct3 := io.Funct3M
  swByteMask.io.ByteOffset := io.IEUAdrM(1, 0)
  writeSwap.io.a := subwordWrite.io.WriteDataWord
  writeSwap.io.BigEndianM := BigEndianM

  val bus = Module(new AHBInterface(false))
  bus.clk := clk
  bus.reset := reset
  bus.io.Stall := io.StallM
  bus.io.Flush := io.BusFlushM
  bus.io.BusRW := Mux(!LocalM && ReadAllowedM, MEM_READ.U(MEM_WIDTH.W),
    Mux(!LocalM && WriteAllowedM, MEM_WRITE.U(MEM_WIDTH.W), MEM_NONE.U(MEM_WIDTH.W)))
  bus.io.HREADY := io.ahb.HREADY
  bus.io.HRDATA := io.ahb.HRDATA
  bus.io.ByteMask := swByteMask.io.ByteMask
  bus.io.WriteData := writeSwap.io.y
  io.BusStallM := bus.io.BusStall
  io.BusCommittedM := bus.io.BusCommitted
  io.ahb.HADDR := io.IEUAdrM.pad(34)
  io.ahb.HWDATA := bus.io.HWDATA
  io.ahb.HWSTRB := bus.io.HWSTRB
  io.ahb.HWRITE := bus.io.HWRITE
  io.ahb.HSIZE := io.Funct3M(1, 0).pad(3)
  io.ahb.HBURST := 0.U(3.W)
  io.ahb.HPROT := 3.U(4.W)
  io.ahb.HTRANS := bus.io.HTRANS
  io.ahb.HMASTLOCK := false.B

  val LocalStore = LocalM && WriteAllowedM && !io.SuppressM && !io.StallM
  val LocalReadWord = WireDefault(0.U(config.xlen.W))
  val LocalReadValid = WireDefault(false.B)
  if (busConfig.dtimSupported) {
    val dtim = Module(new DTIM(config.xlen, config.dmemDepth))
    val ReadIssue = io.MemReadE && LegalReadE && !MisalignedE && LocalE &&
      !io.StallM && !LocalStore
    val ReadArrival = withClockAndReset(clk, reset) { RegNext(ReadIssue, false.B) }
    val ReadValidM = withClockAndReset(clk, reset) { RegInit(false.B) }
    val HeldWord = withClockAndReset(clk, reset) { RegInit(0.U(config.xlen.W)) }
    when(!io.StallM) { ReadValidM := ReadIssue }
    when(ReadArrival) { HeldWord := dtim.io.ReadDataWord }
    dtim.clk := clk
    dtim.io.Adr := Mux(LocalStore, io.IEUAdrM, io.IEUAdrE)
    dtim.io.MemRead := ReadIssue
    dtim.io.MemWrite := LocalStore
    dtim.io.ByteMask := swByteMask.io.ByteMask
    dtim.io.WriteDataWord := writeSwap.io.y
    LocalReadWord := Mux(ReadArrival, dtim.io.ReadDataWord, HeldWord)
    LocalReadValid := ReadValidM
  }

  readSwap.io.a := Mux(LocalM, LocalReadWord, bus.io.FetchBuffer)
  readSwap.io.BigEndianM := BigEndianM
  subwordRead.io.ReadDataWord := readSwap.io.y
  val PhysicalOffsetM = io.IEUAdrM(1, 0)
  val BigEndianOffsetM = MuxLookup(io.Funct3M, PhysicalOffsetM)(Seq(
    LOAD_BYTE.U(FUNCT3_WIDTH.W) -> (PhysicalOffsetM ^ 3.U(2.W)),
    LOAD_BYTE_U.U(FUNCT3_WIDTH.W) -> (PhysicalOffsetM ^ 3.U(2.W)),
    LOAD_HALF.U(FUNCT3_WIDTH.W) -> (PhysicalOffsetM ^ 2.U(2.W)),
    LOAD_HALF_U.U(FUNCT3_WIDTH.W) -> (PhysicalOffsetM ^ 2.U(2.W))
  ))
  subwordRead.io.ByteOffset := Mux(BigEndianM, BigEndianOffsetM, PhysicalOffsetM)
  subwordRead.io.Funct3 := io.Funct3M
  io.ReadDataM := Mux(ReadAllowedM && (!LocalM || LocalReadValid),
    subwordRead.io.ReadData, 0.U(config.xlen.W))

  // ByteMaskM 是架构消费事件；总线实际写载荷早已在地址接受沿缓冲。
  val BusStoreConsume = !LocalM && WriteAllowedM && bus.io.BusCommitted &&
    !bus.io.BusStall && !io.StallM && !io.SuppressM
  io.ByteMaskM := Mux(LocalStore, swByteMask.io.ByteMask,
    Mux(BusStoreConsume, bus.io.HWSTRB, 0.U(BYTES_PER_WORD.W)))
}
