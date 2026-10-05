package riscvpipeline.lsu

import chisel3._
import chisel3.util.MuxLookup
import riscvpipeline.config.CpuConfig

/** E 级同步读请求与 M 级写请求；ReadDataM 与 M 级子字信息对齐，供下一沿写回。 */
final class LSUIO(config: CpuConfig) extends Bundle {
  val MemReadE = Input(Bool())
  val Funct3E = Input(UInt(3.W))
  val IEUAdrE = Input(UInt(config.xlen.W))
  val MemRWM = Input(UInt(2.W))
  val Funct3M = Input(UInt(3.W))
  val IEUAdrM = Input(UInt(config.xlen.W))
  val WriteDataM = Input(UInt(config.xlen.W))
  val ReadDataM = Output(UInt(config.xlen.W))
  val ByteMaskM = Output(UInt(4.W))
}

/** 教材 §7.2.1/图 7.13 的同步 LSU：DTIM E 级提供读地址，M 级提供写地址和数据。
  * RV32 支持 LB/LH/LW/LBU/LHU 和 SB/SH/SW；非法或未对齐读零、不写。
  * RAM 不复位；reset 立即屏蔽请求，并同步清除读响应有效位。
  */
final class LSU(val config: CpuConfig = CpuConfig()) extends RawModule {
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new LSUIO(config))

  val ByteOffsetE = io.IEUAdrE(1, 0)
  val LoadAllowedE = MuxLookup(io.Funct3E, false.B)(Seq(
    0.U -> true.B, 1.U -> !ByteOffsetE(0), 2.U -> (ByteOffsetE === 0.U),
    4.U -> true.B, 5.U -> !ByteOffsetE(0)
  ))
  val swByteMask = Module(new SwByteMask(config.xlen))
  val subwordWrite = Module(new SubwordWrite(config.xlen))
  val dtim = Module(new DTIM(config.xlen, config.dmemDepth))
  val subwordRead = Module(new SubwordRead(config.xlen))

  swByteMask.io.Funct3 := io.Funct3M
  swByteMask.io.ByteOffset := io.IEUAdrM(1, 0)
  subwordWrite.io.Funct3 := io.Funct3M
  subwordWrite.io.WriteData := io.WriteDataM
  val WriteAllowedM = io.MemRWM(0) && swByteMask.io.ByteMask.orR && !reset
  val ReadAllowedE = io.MemReadE && LoadAllowedE && !WriteAllowedM && !reset
  val ReadValidM = withClockAndReset(clk, reset) { RegNext(ReadAllowedE, false.B) }
  dtim.clk := clk
  dtim.io.Adr := Mux(WriteAllowedM, io.IEUAdrM, io.IEUAdrE)
  dtim.io.MemRead := ReadAllowedE
  dtim.io.MemWrite := WriteAllowedM
  dtim.io.ByteMask := swByteMask.io.ByteMask
  dtim.io.WriteDataWord := subwordWrite.io.WriteDataWord

  subwordRead.io.ReadDataWord := dtim.io.ReadDataWord
  subwordRead.io.ByteOffset := io.IEUAdrM(1, 0)
  subwordRead.io.Funct3 := io.Funct3M
  io.ReadDataM := Mux(ReadValidM && !reset, subwordRead.io.ReadData, 0.U(config.xlen.W))
  io.ByteMaskM := Mux(WriteAllowedM, swByteMask.io.ByteMask, 0.U(4.W))
}
