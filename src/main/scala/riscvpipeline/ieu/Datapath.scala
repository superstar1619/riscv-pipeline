package riscvpipeline.ieu

import chisel3._
import chisel3.util.MuxLookup
import riscvpipeline.config.CpuConfig

/** D 级译码、E 级前递/冲刷及 M 级读取结果；所有地址均为字节地址。 */
final class DatapathIO(config: CpuConfig) extends Bundle {
  val InstrD = Input(UInt(32.W))
  val PCD = Input(UInt(config.xlen.W))
  val PCPlus4D = Input(UInt(config.xlen.W))
  val ValidD = Input(Bool())
  val ALUResultSrcD = Input(Bool())
  val JumpD = Input(Bool())
  val BranchD = Input(Bool())
  val ResultSrcD = Input(Bool())
  val ALUSrcD = Input(UInt(2.W))
  val RegWriteD = Input(Bool())
  val ImmSrcD = Input(UInt(3.W))
  val ALUControlD = Input(UInt(2.W))
  val MemRWD = Input(UInt(2.W))
  val FlushE = Input(Bool())
  val ForwardAE = Input(UInt(2.W))
  val ForwardBE = Input(UInt(2.W))
  val ReadDataM = Input(UInt(config.xlen.W))
  val PCSrcE = Output(Bool())
  val IEUAdrE = Output(UInt(config.xlen.W))
  val MemRWE = Output(UInt(2.W))
  val Funct3E = Output(UInt(3.W))
  val Rs1E = Output(UInt(5.W))
  val Rs2E = Output(UInt(5.W))
  val RdE = Output(UInt(5.W))
  val ValidE = Output(Bool())
  val IEUAdrM = Output(UInt(config.xlen.W))
  val WriteDataM = Output(UInt(config.xlen.W))
  val MemRWM = Output(UInt(2.W))
  val Funct3M = Output(UInt(3.W))
  val RdM = Output(UInt(5.W))
  val RegWriteM = Output(Bool())
  val ResultSrcM = Output(Bool())
  val ValidM = Output(Bool())
  val RdW = Output(UInt(5.W))
  val RegWriteW = Output(Bool())
  val ValidW = Output(Bool())
}

private class ExecuteState(config: CpuConfig) extends Bundle {
  val Valid = Bool()
  val R1 = UInt(config.xlen.W)
  val R2 = UInt(config.xlen.W)
  val ImmExt = UInt(config.xlen.W)
  val PC = UInt(config.xlen.W)
  val PCPlus4 = UInt(config.xlen.W)
  val Rs1 = UInt(5.W)
  val Rs2 = UInt(5.W)
  val Rd = UInt(5.W)
  val Funct3 = UInt(3.W)
  val ALUResultSrc = Bool()
  val Jump = Bool()
  val Branch = Bool()
  val IsJalr = Bool()
  val ResultSrc = Bool()
  val ALUSrc = UInt(2.W)
  val RegWrite = Bool()
  val ALUControl = UInt(2.W)
  val MemRW = UInt(2.W)
}

private class MemoryState(config: CpuConfig) extends Bundle {
  val Valid = Bool()
  val IEUAdr = UInt(config.xlen.W)
  val IEUResult = UInt(config.xlen.W)
  val WriteData = UInt(config.xlen.W)
  val Rd = UInt(5.W)
  val Funct3 = UInt(3.W)
  val ResultSrc = Bool()
  val RegWrite = Bool()
  val MemRW = UInt(2.W)
}

private class WritebackState(config: CpuConfig) extends Bundle {
  val Valid = Bool()
  val Result = UInt(config.xlen.W)
  val Rd = UInt(5.W)
  val RegWrite = Bool()
}

/** 教材 §7.2–7.3 的 D/E/M/W 数据通路；同步上升沿推进，复位清空流水状态。
  * 寄存器文件仍在上升沿写入，显式 W→D 旁路保证同周期译码读取最新结果。
  * M/W 前递同时用于 ALU、分支比较、JALR 基址和 store 数据；链接使用本指令 PC+4。
  */
final class Datapath(val config: CpuConfig = CpuConfig()) extends RawModule {
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new DatapathIO(config))

  private val StateE = withClockAndReset(clk, reset) {
    RegInit(0.U.asTypeOf(new ExecuteState(config)))
  }
  private val StateM = withClockAndReset(clk, reset) {
    RegInit(0.U.asTypeOf(new MemoryState(config)))
  }
  private val StateW = withClockAndReset(clk, reset) {
    RegInit(0.U.asTypeOf(new WritebackState(config)))
  }
  val rf = Module(new RegFile(config.xlen, registerCount = 32))
  val ext = Module(new Extend(config.xlen))
  val cmp = Module(new Cmp(config.xlen))
  val alu = Module(new ALU(config.xlen))

  val Rs1D = io.InstrD(19, 15)
  val Rs2D = io.InstrD(24, 20)
  val RegWriteW = StateW.Valid && StateW.RegWrite && !reset
  val ResultW = StateW.Result
  rf.clk := clk
  rf.reset := reset
  rf.io.WE3 := RegWriteW
  rf.io.A1 := Rs1D
  rf.io.A2 := Rs2D
  rf.io.A3 := StateW.Rd
  rf.io.WD3 := ResultW

  private def decodeRead(source: UInt, data: UInt): UInt =
    Mux(source === 0.U, 0.U(config.xlen.W),
      Mux(RegWriteW && StateW.Rd =/= 0.U && source === StateW.Rd, ResultW, data))
  val R1D = decodeRead(Rs1D, rf.io.RD1)
  val R2D = decodeRead(Rs2D, rf.io.RD2)
  ext.io.Instr := io.InstrD(31, 7)
  ext.io.ImmSrc := io.ImmSrcD

  when(io.FlushE) {
    StateE := 0.U.asTypeOf(StateE)
  }.otherwise {
    StateE.Valid := io.ValidD
    StateE.R1 := R1D
    StateE.R2 := R2D
    StateE.ImmExt := ext.io.ImmExt
    StateE.PC := io.PCD
    StateE.PCPlus4 := io.PCPlus4D
    StateE.Rs1 := Rs1D
    StateE.Rs2 := Rs2D
    StateE.Rd := io.InstrD(11, 7)
    StateE.Funct3 := io.InstrD(14, 12)
    StateE.ALUResultSrc := io.ALUResultSrcD
    StateE.Jump := io.JumpD
    StateE.Branch := io.BranchD
    StateE.IsJalr := io.InstrD(6, 0) === 0x67.U
    StateE.ResultSrc := io.ResultSrcD
    StateE.ALUSrc := io.ALUSrcD
    StateE.RegWrite := io.RegWriteD
    StateE.ALUControl := io.ALUControlD
    StateE.MemRW := io.MemRWD
  }

  private def forwarded(select: UInt, original: UInt): UInt =
    MuxLookup(select, original)(Seq(1.U -> ResultW, 2.U -> StateM.IEUResult))
  val ForwardedSrcAE = forwarded(io.ForwardAE, StateE.R1)
  val ForwardedSrcBE = forwarded(io.ForwardBE, StateE.R2)
  cmp.io.R1 := ForwardedSrcAE
  cmp.io.R2 := ForwardedSrcBE
  val BranchFlagE = MuxLookup(StateE.Funct3(2, 1), false.B)(Seq(
    0.U -> cmp.io.Eq, 2.U -> cmp.io.LT, 3.U -> cmp.io.LTU
  ))
  val BranchTakenE = BranchFlagE ^ StateE.Funct3(0)
  io.PCSrcE := StateE.Valid && !reset &&
    (StateE.Jump || (StateE.Branch && BranchTakenE))

  alu.io.SrcA := Mux(StateE.ALUSrc(1), StateE.PC, ForwardedSrcAE)
  alu.io.SrcB := Mux(StateE.ALUSrc(0), StateE.ImmExt, ForwardedSrcBE)
  alu.io.ALUControl := StateE.ALUControl
  alu.io.Funct3 := StateE.Funct3
  val IEUAdrE = Mux(StateE.IsJalr,
    alu.io.IEUAdr & "hfffffffe".U(config.xlen.W), alu.io.IEUAdr)
  val AltResultE = Mux(StateE.Jump, StateE.PCPlus4, StateE.ImmExt)
  val IEUResultE = Mux(StateE.ALUResultSrc, AltResultE, alu.io.ALUResult)

  StateM.Valid := StateE.Valid
  StateM.IEUAdr := IEUAdrE
  StateM.IEUResult := IEUResultE
  StateM.WriteData := ForwardedSrcBE
  StateM.Rd := StateE.Rd
  StateM.Funct3 := StateE.Funct3
  StateM.ResultSrc := StateE.ResultSrc
  StateM.RegWrite := StateE.RegWrite
  StateM.MemRW := StateE.MemRW

  StateW.Valid := StateM.Valid
  StateW.Result := Mux(StateM.ResultSrc, io.ReadDataM, StateM.IEUResult)
  StateW.Rd := StateM.Rd
  StateW.RegWrite := StateM.RegWrite

  io.IEUAdrE := IEUAdrE
  io.MemRWE := Mux(StateE.Valid && !reset, StateE.MemRW, 0.U(2.W))
  io.Funct3E := StateE.Funct3
  io.Rs1E := StateE.Rs1
  io.Rs2E := StateE.Rs2
  io.RdE := StateE.Rd
  io.ValidE := StateE.Valid
  io.IEUAdrM := StateM.IEUAdr
  io.WriteDataM := StateM.WriteData
  io.MemRWM := Mux(StateM.Valid && !reset, StateM.MemRW, 0.U(2.W))
  io.Funct3M := StateM.Funct3
  io.RdM := StateM.Rd
  io.RegWriteM := StateM.RegWrite
  io.ResultSrcM := StateM.ResultSrc
  io.ValidM := StateM.Valid
  io.RdW := StateW.Rd
  io.RegWriteW := RegWriteW
  io.ValidW := StateW.Valid
}
