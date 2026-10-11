package riscvpipeline.ifu

import chisel3._
import riscvpipeline.config.CpuConfig
import riscvpipeline.isa.Architecture._

/** F 级 PC 控制与 D 级同步取指结果；PC/目标均为字节地址。 */
final class IFUIO(config: CpuConfig) extends Bundle {
  val RedirectM = Input(Bool())
  val RedirectPCM = Input(UInt(config.xlen.W))
  val PCSrcE = Input(Bool())
  val IEUAdrE = Input(UInt(config.xlen.W))
  val StallF = Input(Bool())
  val StallD = Input(Bool())
  val FlushD = Input(Bool())
  val PCF = Output(UInt(config.xlen.W))
  val InstrD = Output(UInt(INSTRUCTION_WIDTH.W))
  val PCD = Output(UInt(config.xlen.W))
  val PCPlus4D = Output(UInt(config.xlen.W))
  val ValidD = Output(Bool())
  val InstrAccessFaultD = Output(Bool())
}

/** 教材 §7.2–7.3 的 F/D 取指：默认顺序预测，E 重定向冲刷年轻指令。
  * IROM 直接读 PCF 到 D，PCD 在同一个上升沿采样 PCF，无额外指令寄存器。
  * 同步复位清空 ValidD、恢复 resetVector；重定向优先于 F/D 停顿。
  */
final class IFU(val config: CpuConfig = CpuConfig()) extends RawModule {
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new IFUIO(config))
  val PCF = withClockAndReset(clk, reset) {
    RegInit(config.resetVector.U(config.xlen.W))
  }
  val PCD = withClockAndReset(clk, reset) { RegInit(0.U(config.xlen.W)) }
  val ValidD = withClockAndReset(clk, reset) { RegInit(false.B) }
  val FetchFaultD = withClockAndReset(clk, reset) { RegInit(false.B) }
  private val ROM_CAPACITY = BigInt(config.imemDepth) * INSTRUCTION_BYTES
  private val ROM_BASE = config.resetVector / ROM_CAPACITY * ROM_CAPACITY
  val FetchAccessFaultF = config.privilegedSupported.B &&
    (PCF.pad(XLEN + 1) < ROM_BASE.U((XLEN + 1).W) ||
      PCF.pad(XLEN + 1) >= (ROM_BASE + ROM_CAPACITY).U((XLEN + 1).W))
  val irom = Module(new IROM(config))
  val RedirectE = io.RedirectM || io.PCSrcE || io.FlushD

  when(io.RedirectM) {
    PCF := io.RedirectPCM
  }.elsewhen(io.PCSrcE) {
    PCF := io.IEUAdrE
  }.elsewhen(!io.StallF) {
    PCF := PCF + INSTRUCTION_BYTES.U(config.xlen.W)
  }
  when(RedirectE) {
    ValidD := false.B
    FetchFaultD := false.B
  }.elsewhen(!io.StallD) {
    PCD := PCF
    ValidD := true.B
    FetchFaultD := FetchAccessFaultF
  }
  irom.clk := clk
  irom.io.a := PCF
  irom.io.en := !reset && !io.StallD && !RedirectE
  io.PCF := PCF
  io.PCD := PCD
  io.PCPlus4D := PCD + INSTRUCTION_BYTES.U(config.xlen.W)
  io.InstrD := irom.io.rd
  io.ValidD := ValidD
  io.InstrAccessFaultD := FetchFaultD
}
