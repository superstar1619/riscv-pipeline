package riscvpipeline

import chisel3._
import riscvpipeline.config.CpuConfig
import riscvpipeline.ifu.IFU
import riscvpipeline.ieu.IEU
import riscvpipeline.lsu.LSU

/** M 级观察接口，沿用原端口位宽；ByteMask/MemWrite 表示实际允许的写入。 */
final class RiscvPipelineIO(config: CpuConfig) extends Bundle {
  val WriteData = Output(UInt(config.xlen.W))
  val IEUAdr = Output(UInt(config.xlen.W))
  val MemWrite = Output(Bool())
  val MemRW = Output(UInt(2.W))
  val Funct3 = Output(UInt(3.W))
  val ByteMask = Output(UInt(4.W))
}

/** 教材 §7.2–7.3 的 RV32I 五级流水线，参考 CVW 的 IFU/IEU/LSU 连接。
  * 显式 clk、Bool 高有效同步复位；清空流水有效位，RAM/非零寄存器保值。
  * 取指/数据同步读，E 级重定向，W 级寄存器写回，M 级存储提交。
  */
final class RiscvPipeline(val config: CpuConfig = CpuConfig()) extends RawModule {
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new RiscvPipelineIO(config))
  val ifu = Module(new IFU(config))
  val ieu = Module(new IEU(config))
  val lsu = Module(new LSU(config))

  ifu.clk := clk
  ifu.reset := reset
  ifu.io.PCSrcE := ieu.io.PCSrcE
  ifu.io.IEUAdrE := ieu.io.IEUAdrE
  ifu.io.StallF := ieu.io.StallF
  ifu.io.StallD := ieu.io.StallD
  ifu.io.FlushD := ieu.io.FlushD
  ieu.clk := clk
  ieu.reset := reset
  ieu.io.InstrD := ifu.io.InstrD
  ieu.io.PCD := ifu.io.PCD
  ieu.io.PCPlus4D := ifu.io.PCPlus4D
  ieu.io.ValidD := ifu.io.ValidD
  ieu.io.ReadDataM := lsu.io.ReadDataM

  lsu.clk := clk
  lsu.reset := reset
  lsu.io.MemReadE := ieu.io.MemRWE(1)
  lsu.io.Funct3E := ieu.io.Funct3E
  lsu.io.IEUAdrE := ieu.io.IEUAdrE
  lsu.io.MemRWM := ieu.io.MemRWM
  lsu.io.Funct3M := ieu.io.Funct3M
  lsu.io.IEUAdrM := ieu.io.IEUAdrM
  lsu.io.WriteDataM := ieu.io.WriteDataM

  io.WriteData := ieu.io.WriteDataM
  io.IEUAdr := ieu.io.IEUAdrM
  io.MemRW := ieu.io.MemRWM
  io.Funct3 := ieu.io.Funct3M
  io.ByteMask := lsu.io.ByteMaskM
  io.MemWrite := lsu.io.ByteMaskM.orR
}
