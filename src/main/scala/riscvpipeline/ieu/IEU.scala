package riscvpipeline.ieu

import chisel3._
import riscvpipeline.config.CpuConfig
import riscvpipeline.control.ControlEncoding._
import riscvpipeline.hazard.Hazard
import riscvpipeline.isa.Architecture._
import riscvpipeline.isa.InstructionDecode
import riscvpipeline.isa.InstructionEncoding._

/** D 级指令/PC 与 M 级存储结果输入；E 级重定向和 M 级访存输出。 */
final class IEUIO(config: CpuConfig) extends Bundle {
  val InstrD = Input(UInt(INSTRUCTION_WIDTH.W))
  val InstrAccessFaultD = Input(Bool())
  val StallM = Input(Bool())
  val FlushM = Input(Bool())
  val TrapM = Input(Bool())
  val CSRResultM = Input(UInt(XLEN.W))
  val PCD = Input(UInt(config.xlen.W))
  val PCPlus4D = Input(UInt(config.xlen.W))
  val ValidD = Input(Bool())
  val ReadDataM = Input(UInt(config.xlen.W))
  val InstrM = Output(UInt(INSTRUCTION_WIDTH.W))
  val PCM = Output(UInt(XLEN.W))
  val SrcAM = Output(UInt(XLEN.W))
  val InstrAccessFaultM = Output(Bool())
  val InstrMisalignedFaultM = Output(Bool())
  val IllegalIEUInstrM = Output(Bool())
  val ValidM = Output(Bool())
  val wfiW = Output(Bool())
  val PCSrcE = Output(Bool())
  val IEUAdrE = Output(UInt(config.xlen.W))
  val MemRWE = Output(UInt(MEM_WIDTH.W))
  val Funct3E = Output(UInt(FUNCT3_WIDTH.W))
  val IEUAdrM = Output(UInt(config.xlen.W))
  val WriteDataM = Output(UInt(config.xlen.W))
  val MemRWM = Output(UInt(MEM_WIDTH.W))
  val Funct3M = Output(UInt(FUNCT3_WIDTH.W))
  val LoadStallD = Output(Bool())
  val StoreStallD = Output(Bool())
  val StallF = Output(Bool())
  val StallD = Output(Bool())
  val FlushD = Output(Bool())
}

/** 教材 §7.2–7.3：D 译码、E 执行/跳转、M 保存结果、W 写回。
  * 独立 Hazard 负责前递、load-use、store→load 与重定向优先级。
  * 按合法译码和实际源使用检测依赖，避免立即数位型被误认为 Rs2。
  */
final class IEU(val config: CpuConfig = CpuConfig()) extends RawModule {
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new IEUIO(config))
  val c = Module(new Controller)
  val dp = Module(new Datapath(config))
  val hazard = Module(new Hazard)

  val OpD = io.InstrD(6, 0)
  c.io.Op := OpD
  c.io.Funct3 := io.InstrD(14, 12)
  c.io.Funct7 := io.InstrD(31, 25)
  // 分支比较在 E 级完成；D 级只使用静态译码输出。
  c.io.Eq := false.B
  c.io.LT := false.B
  c.io.LTU := false.B

  dp.clk := clk
  dp.reset := reset
  val SystemD = OpD === OP_SYSTEM.U(OPCODE_WIDTH.W)
  val CSRInstrD = InstructionDecode.isCSR(io.InstrD)
  val FenceD = InstructionDecode.isFence(io.InstrD)
  val IntegerLegalD = c.io.RegWrite || c.io.Branch || c.io.Jump || c.io.MemRW.orR
  dp.io.InstrD := io.InstrD
  dp.io.InstrAccessFaultD := io.InstrAccessFaultD
  dp.io.IllegalIEUInstrD := !IntegerLegalD && !SystemD && !FenceD
  dp.io.StallM := io.StallM
  dp.io.FlushM := io.FlushM
  dp.io.TrapM := io.TrapM
  dp.io.CSRResultM := io.CSRResultM
  dp.io.PCD := io.PCD
  dp.io.PCPlus4D := io.PCPlus4D
  dp.io.ValidD := io.ValidD
  dp.io.ALUResultSrcD := c.io.ALUResultSrc
  dp.io.JumpD := c.io.Jump
  dp.io.BranchD := c.io.Branch
  dp.io.ResultSrcD := c.io.ResultSrc
  dp.io.ALUSrcD := c.io.ALUSrc
  dp.io.RegWriteD := c.io.RegWrite || (config.privilegedSupported.B && CSRInstrD)
  dp.io.ImmSrcD := c.io.ImmSrc
  dp.io.ALUControlD := c.io.ALUControl
  dp.io.MemRWD := c.io.MemRW
  dp.io.ReadDataM := io.ReadDataM
  dp.io.FlushE := hazard.io.FlushE
  dp.io.ForwardAE := hazard.io.ForwardAE
  dp.io.ForwardBE := hazard.io.ForwardBE

  val UsesRs1D = (c.io.RegWrite &&
    (OpD === OP_REG.U(OPCODE_WIDTH.W) || OpD === OP_IMM.U(OPCODE_WIDTH.W) ||
      OpD === OP_LOAD.U(OPCODE_WIDTH.W) || OpD === OP_JALR.U(OPCODE_WIDTH.W))) ||
    c.io.MemRW(MEM_WRITE_BIT) || c.io.Branch ||
    (config.privilegedSupported.B && CSRInstrD && !io.InstrD(14))
  val UsesRs2D = (c.io.RegWrite && OpD === OP_REG.U(OPCODE_WIDTH.W)) ||
    c.io.MemRW(MEM_WRITE_BIT) || c.io.Branch
  hazard.io.Rs1D := io.InstrD(19, 15)
  hazard.io.Rs2D := io.InstrD(24, 20)
  hazard.io.UsesRs1D := UsesRs1D
  hazard.io.UsesRs2D := UsesRs2D
  hazard.io.ValidD := io.ValidD && !reset
  hazard.io.MemRWD := c.io.MemRW
  hazard.io.Rs1E := dp.io.Rs1E
  hazard.io.Rs2E := dp.io.Rs2E
  hazard.io.RdE := dp.io.RdE
  hazard.io.ValidE := dp.io.ValidE
  hazard.io.MemRWE := dp.io.MemRWE
  hazard.io.LateResultE := dp.io.CSRInstrE
  hazard.io.RdM := dp.io.RdM
  hazard.io.RegWriteM := dp.io.RegWriteM
  hazard.io.ValidM := dp.io.ValidM
  hazard.io.ResultSrcM := dp.io.ResultSrcM
  hazard.io.RdW := dp.io.RdW
  hazard.io.RegWriteW := dp.io.RegWriteW
  hazard.io.ValidW := dp.io.ValidW
  hazard.io.PCSrcE := dp.io.PCSrcE

  io.InstrM := dp.io.InstrM
  io.PCM := dp.io.PCM
  io.SrcAM := dp.io.SrcAM
  io.InstrAccessFaultM := dp.io.InstrAccessFaultM
  io.InstrMisalignedFaultM := dp.io.InstrMisalignedFaultM
  io.IllegalIEUInstrM := dp.io.IllegalIEUInstrM
  io.ValidM := dp.io.ValidM
  io.wfiW := dp.io.wfiW
  io.PCSrcE := dp.io.PCSrcE
  io.IEUAdrE := dp.io.IEUAdrE
  io.MemRWE := dp.io.MemRWE
  io.Funct3E := dp.io.Funct3E
  io.IEUAdrM := dp.io.IEUAdrM
  io.WriteDataM := dp.io.WriteDataM
  io.MemRWM := dp.io.MemRWM
  io.Funct3M := dp.io.Funct3M
  io.LoadStallD := hazard.io.LoadStallD
  io.StoreStallD := hazard.io.StoreStallD
  io.StallF := hazard.io.StallF || io.StallM
  io.StallD := hazard.io.StallD || io.StallM
  io.FlushD := hazard.io.FlushD || io.FlushM
}
