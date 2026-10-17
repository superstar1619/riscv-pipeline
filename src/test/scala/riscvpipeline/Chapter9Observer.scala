package riscvpipeline

import chisel3._
import chisel3.util.{MuxCase, RegEnable}
import chisel3.stage.ChiselStage
import chiseltest.experimental.expose
import riscvpipeline.config.{BusConfig, CpuConfig}
import riscvpipeline.privileged.InterruptInputs

/** 仅在 Test 源集中生成的证据端口，不向生产 SoC 添加调试接口。
  * commit 元数据在实际 M→W 接受沿保存；设备事件来自全局 Data 完成沿。
  */
final class Chapter9Observer(config: CpuConfig) extends Module {
  val interrupts = IO(Input(new InterruptInputs))
  val dut = Module(new RiscvPipelineSoC(config))
  dut.clk := clock
  dut.reset := reset.asBool
  dut.interrupts := interrupts

  val validM = expose(dut.core.ieu.io.ValidM)
  val pcM = expose(dut.core.ieu.io.PCM)
  val instructionM = expose(dut.core.ieu.io.InstrM)
  val stallM = expose(dut.core.StallM)
  val trapM = expose(dut.core.TrapM)
  val validW = expose(dut.core.ieu.dp.io.ValidW)
  val regWriteW = expose(dut.core.ieu.dp.io.RegWriteW)
  val rdW = expose(dut.core.ieu.dp.io.RdW)
  val resultW = expose(dut.core.ieu.dp.ResultW)
  val causeM = expose(dut.core.privileged.get.io.CauseM)
  val interruptM = expose(dut.core.privileged.get.io.InterruptM)
  val ifuStall = expose(dut.core.ifu.io.BusStallF)
  val lsuStall = expose(dut.core.lsu.io.BusStallM)
  val ifuCommitted = expose(dut.core.ifu.io.BusCommittedF)
  val lsuCommitted = expose(dut.core.lsu.io.BusCommittedM)
  val acceptM = validM && !stallM && !trapM
  val committedPC = RegEnable(pcM, 0.U(32.W), acceptM)
  val committedInstruction = RegEnable(instructionM, 0.U(32.W), acceptM)

  val commit_valid = IO(Output(Bool()))
  val commit_pc32 = IO(Output(UInt(32.W)))
  val commit_instruction32 = IO(Output(UInt(32.W)))
  val commit_rd5 = IO(Output(UInt(5.W)))
  val commit_wdata32 = IO(Output(UInt(32.W)))
  val commit_trap = IO(Output(Bool()))
  val trap_pc32 = IO(Output(UInt(32.W)))
  val trap_cause6 = IO(Output(UInt(6.W)))
  val trap_interrupt = IO(Output(Bool()))
  commit_valid := validW
  commit_pc32 := committedPC
  commit_instruction32 := committedInstruction
  commit_rd5 := Mux(regWriteW, rdW, 0.U(5.W))
  commit_wdata32 := Mux(regWriteW && rdW =/= 0.U, resultW, 0.U(32.W))
  commit_trap := trapM && !stallM
  trap_pc32 := pcM
  trap_cause6 := causeM
  trap_interrupt := interruptM

  val store_valid = IO(Output(Bool()))
  val store_addr32 = IO(Output(UInt(32.W)))
  val store_data32 = IO(Output(UInt(32.W)))
  val store_mask4 = IO(Output(UInt(4.W)))
  store_valid := dut.io.MemWrite
  store_addr32 := dut.io.IEUAdr
  store_data32 := dut.io.WriteData
  store_mask4 := dut.io.ByteMask

  val ready = expose(dut.core.ahb.HREADY)
  val address = expose(dut.core.ahb.HADDR)
  val transfer = expose(dut.core.ahb.HTRANS)
  val write = expose(dut.core.ahb.HWRITE)
  val protection = expose(dut.core.ahb.HPROT)
  val writeData = expose(dut.core.ahb.HWDATA)
  val readData = expose(dut.core.ahb.HRDATA)
  val byteMask = expose(dut.core.ahb.HWSTRB)
  val DataValid = RegInit(false.B)
  val DataAddress = RegInit(0.U(34.W))
  val DataWrite = RegInit(false.B)
  val DataProtection = RegInit(0.U(4.W))
  when(ready) {
    DataValid := transfer(1)
    when(transfer(1)) {
      DataAddress := address
      DataWrite := write
      DataProtection := protection
    }
  }
  val busConfig = config.bus.get
  def inWindow(base: BigInt, bytes: BigInt): Bool =
    DataAddress.pad(35) >= base.U(35.W) &&
      DataAddress.pad(35) < (base + bytes).U(35.W)
  val InROM = inWindow(busConfig.bootRomBase, busConfig.bootRomBytes)
  val InRAM = inWindow(busConfig.ramBase, busConfig.ramBytes)
  val InAPB = inWindow(busConfig.apbBase, busConfig.apbBytes)
  val Instruction = !DataProtection(0)
  val event_valid = IO(Output(Bool()))
  val event_kind4 = IO(Output(UInt(4.W)))
  val event_addr32 = IO(Output(UInt(32.W)))
  val event_wdata32 = IO(Output(UInt(32.W)))
  val event_rdata32 = IO(Output(UInt(32.W)))
  val event_mask4 = IO(Output(UInt(4.W)))
  event_valid := DataValid && ready
  event_kind4 := MuxCase(15.U(4.W), Seq(
    (InROM && Instruction) -> 0.U(4.W),
    (InRAM && Instruction) -> 1.U(4.W),
    (InRAM && !DataWrite) -> 2.U(4.W),
    (InRAM && DataWrite) -> 3.U(4.W),
    (InAPB && !DataWrite) -> 4.U(4.W),
    (InAPB && DataWrite) -> 5.U(4.W),
    InROM -> 6.U(4.W)))
  event_addr32 := DataAddress(31, 0)
  event_wdata32 := writeData
  event_rdata32 := readData
  event_mask4 := byteMask

  val bus_wait = IO(Output(Bool()))
  val ifu_wait = IO(Output(Bool()))
  val lsu_wait = IO(Output(Bool()))
  val committed_f = IO(Output(Bool()))
  val committed_m = IO(Output(Bool()))
  bus_wait := !ready
  ifu_wait := ifuStall
  lsu_wait := lsuStall
  committed_f := ifuCommitted
  committed_m := lsuCommitted
}

object GenerateChapter9Observer extends App {
  require(args.length == 5, "targetDir imagePath bus|mixed ramLatency apbLatency")
  require(args(2) == "bus" || args(2) == "mixed", "mode must be bus or mixed")
  val mixed = args(2) == "mixed"
  val image = Some(args(1))
  val bus = BusConfig(bootRomInitFile = image, ramLatency = args(3).toInt,
    apbLatency = args(4).toInt, iromSupported = mixed, dtimSupported = mixed)
  val config = CpuConfig(imemDepth = 1024, dmemDepth = 1024,
    resetVector = if (mixed) 0 else 0x1000, instructionInitFile = image,
    privilegedSupported = true, bus = Some(bus))
  (new ChiselStage).emitVerilog(new Chapter9Observer(config), Array("--target-dir", args(0)))
}
