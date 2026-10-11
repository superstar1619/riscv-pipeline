package riscvpipeline

import chisel3._
import riscvpipeline.config.CpuConfig
import riscvpipeline.control.ControlEncoding.MEM_WIDTH
import riscvpipeline.isa.Architecture.{BYTES_PER_WORD, FUNCT3_WIDTH}

/** M 级观察接口，沿用原端口位宽；ByteMask/MemWrite 表示实际允许的写入。 */
final class RiscvPipelineIO(config: CpuConfig) extends Bundle {
  val WriteData = Output(UInt(config.xlen.W))
  val IEUAdr = Output(UInt(config.xlen.W))
  val MemWrite = Output(Bool())
  val MemRW = Output(UInt(MEM_WIDTH.W))
  val Funct3 = Output(UInt(FUNCT3_WIDTH.W))
  val ByteMask = Output(UInt(BYTES_PER_WORD.W))
}

/** 保留第七章顶层端口；特权配置可执行同步陷阱，外部中断接零。
  * 需要中断/time输入时使用PrivilegedPipeline，共享PipelineBase实现。
  */
final class RiscvPipeline(val config: CpuConfig = CpuConfig()) extends PipelineBase(config) {
  Interrupts := 0.U.asTypeOf(Interrupts)
}
