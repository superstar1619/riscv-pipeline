package riscvpipeline.ieu

import chisel3._
import chisel3.util.{Cat, MuxLookup}
import riscvpipeline.control.ControlEncoding._
import riscvpipeline.isa.Architecture._
import riscvpipeline.isa.InstructionEncoding._

/** RV32 功能字段、寄存器比较标志与数据通路控制；编码位宽与指令字段一致。 */
final class ControllerIO extends Bundle {
  val Op = Input(UInt(OPCODE_WIDTH.W))
  val Eq = Input(Bool())
  val LT = Input(Bool())
  val LTU = Input(Bool())
  val Funct3 = Input(UInt(FUNCT3_WIDTH.W))
  val Funct7 = Input(UInt(FUNCT7_WIDTH.W))
  val ALUResultSrc = Output(Bool())
  val ResultSrc = Output(Bool())
  val MemWrite = Output(Bool())
  val MemRW = Output(UInt(MEM_WIDTH.W))
  val Jump = Output(Bool())
  val Branch = Output(Bool())
  val PCSrc = Output(Bool())
  val RegWrite = Output(Bool())
  val ALUSrc = Output(UInt(ALU_SOURCE_WIDTH.W))
  val ImmSrc = Output(UInt(IMM_SOURCE_WIDTH.W))
  val ALUControl = Output(UInt(ALU_CONTROL_WIDTH.W))
}

/** 教材第 7 章 §7.1.4，pp. 311–314：RV32I 纯组合译码，无时钟、复位或状态。
  * 完整功能字段的合法性门控所有控制输出；不支持的指令输出零控制，不产生异常。
  */
final class Controller extends RawModule {
  import Controller.ControlWord

  val io = IO(new ControllerIO)

  private val CONTROL_WORD_WIDTH = 13
  val Controls = Wire(UInt(CONTROL_WORD_WIDTH.W)).suggestName("controls")
  val Branch = Wire(Bool())
  val Jump = Wire(Bool())
  val BranchTaken = Wire(Bool())
  val ALUOp = Wire(Bool())
  val SubArith = Wire(Bool())
  val LegalInstr = Wire(Bool())

  val RLegal = io.Funct7 === FUNCT7_BASE.U(FUNCT7_WIDTH.W) ||
    (io.Funct7 === FUNCT7_SUB_SRA.U(FUNCT7_WIDTH.W) &&
      (io.Funct3 === ALU_ADD_SUB.U(FUNCT3_WIDTH.W) ||
        io.Funct3 === ALU_SRL_SRA.U(FUNCT3_WIDTH.W)))
  val ILegal = (io.Funct3 =/= ALU_SLL.U(FUNCT3_WIDTH.W) &&
    io.Funct3 =/= ALU_SRL_SRA.U(FUNCT3_WIDTH.W)) ||
    (io.Funct3 === ALU_SLL.U(FUNCT3_WIDTH.W) &&
      io.Funct7 === FUNCT7_BASE.U(FUNCT7_WIDTH.W)) ||
    (io.Funct3 === ALU_SRL_SRA.U(FUNCT3_WIDTH.W) &&
      (io.Funct7 === FUNCT7_BASE.U(FUNCT7_WIDTH.W) ||
        io.Funct7 === FUNCT7_SUB_SRA.U(FUNCT7_WIDTH.W)))
  val LoadLegal = Seq(LOAD_BYTE, LOAD_HALF, LOAD_WORD, LOAD_BYTE_U, LOAD_HALF_U)
    .map(code => io.Funct3 === code.U(FUNCT3_WIDTH.W)).reduce(_ || _)
  val StoreLegal = Seq(STORE_BYTE, STORE_HALF, STORE_WORD)
    .map(code => io.Funct3 === code.U(FUNCT3_WIDTH.W)).reduce(_ || _)
  val BranchLegal = Seq(BRANCH_EQ, BRANCH_NE, BRANCH_LT, BRANCH_GE, BRANCH_LTU,
    BRANCH_GEU).map(code => io.Funct3 === code.U(FUNCT3_WIDTH.W)).reduce(_ || _)
  LegalInstr := MuxLookup(io.Op, false.B)(Seq(
    OP_REG.U(OPCODE_WIDTH.W) -> RLegal,
    OP_IMM.U(OPCODE_WIDTH.W) -> ILegal,
    OP_LOAD.U(OPCODE_WIDTH.W) -> LoadLegal,
    OP_STORE.U(OPCODE_WIDTH.W) -> StoreLegal,
    OP_BRANCH.U(OPCODE_WIDTH.W) -> BranchLegal,
    OP_JAL.U(OPCODE_WIDTH.W) -> true.B,
    OP_JALR.U(OPCODE_WIDTH.W) -> (io.Funct3 === JALR_FUNCT3.U(FUNCT3_WIDTH.W)),
    OP_LUI.U(OPCODE_WIDTH.W) -> true.B,
    OP_AUIPC.U(OPCODE_WIDTH.W) -> true.B
  ))

  // 位布局保持教材控制总线：RegWrite_ImmSrc_ALUSrc_ALUOp_ALUResultSrc_
  // MemRW_ResultSrc_Branch_Jump。默认字段及非法指令控制均为零。
  private val RControl = ControlWord(regWrite = true, aluOp = true)
  private val IControl = ControlWord(regWrite = true, aluSrc = ALU_SRC_IMM,
    aluOp = true)
  private val LoadControl = ControlWord(regWrite = true, aluSrc = ALU_SRC_IMM,
    memRW = MEM_READ, resultSrc = true)
  private val StoreControl = ControlWord(immSrc = IMM_S, aluSrc = ALU_SRC_IMM,
    memRW = MEM_WRITE)
  private val BranchControl = ControlWord(immSrc = IMM_B, aluSrc = ALU_SRC_PC_IMM,
    branch = true)
  private val JalControl = ControlWord(regWrite = true, immSrc = IMM_J,
    aluSrc = ALU_SRC_PC_IMM, aluResultSrc = true, jump = true)
  private val JalrControl = ControlWord(regWrite = true, aluSrc = ALU_SRC_IMM,
    aluResultSrc = true, jump = true)
  private val LuiControl = ControlWord(regWrite = true, immSrc = IMM_U,
    aluResultSrc = true)
  private val AuipcControl = ControlWord(regWrite = true, immSrc = IMM_U,
    aluSrc = ALU_SRC_PC_IMM)
  Controls := Mux(LegalInstr, MuxLookup(io.Op, 0.U(CONTROL_WORD_WIDTH.W))(Seq(
    OP_REG.U(OPCODE_WIDTH.W) -> RControl.packed.U(CONTROL_WORD_WIDTH.W),
    OP_IMM.U(OPCODE_WIDTH.W) -> IControl.packed.U(CONTROL_WORD_WIDTH.W),
    OP_LOAD.U(OPCODE_WIDTH.W) -> LoadControl.packed.U(CONTROL_WORD_WIDTH.W),
    OP_STORE.U(OPCODE_WIDTH.W) -> StoreControl.packed.U(CONTROL_WORD_WIDTH.W),
    OP_BRANCH.U(OPCODE_WIDTH.W) -> BranchControl.packed.U(CONTROL_WORD_WIDTH.W),
    OP_JAL.U(OPCODE_WIDTH.W) -> JalControl.packed.U(CONTROL_WORD_WIDTH.W),
    OP_JALR.U(OPCODE_WIDTH.W) -> JalrControl.packed.U(CONTROL_WORD_WIDTH.W),
    OP_LUI.U(OPCODE_WIDTH.W) -> LuiControl.packed.U(CONTROL_WORD_WIDTH.W),
    OP_AUIPC.U(OPCODE_WIDTH.W) -> AuipcControl.packed.U(CONTROL_WORD_WIDTH.W)
  )), 0.U(CONTROL_WORD_WIDTH.W))

  io.RegWrite := Controls(12)
  io.ImmSrc := Controls(11, 9)
  io.ALUSrc := Controls(8, 7)
  ALUOp := Controls(6)
  io.ALUResultSrc := Controls(5)
  io.MemRW := Controls(4, 3)
  io.MemWrite := io.MemRW(MEM_WRITE_BIT)
  io.ResultSrc := Controls(2)
  Branch := Controls(1)
  io.Branch := Branch
  Jump := Controls(0)
  io.Jump := Jump

  val BranchFlag = Wire(Bool())
  BranchFlag := MuxLookup(io.Funct3(2, 1), false.B)(Seq(
    BRANCH_COMPARE_EQ.U(BRANCH_COMPARE_WIDTH.W) -> io.Eq,
    BRANCH_COMPARE_RESERVED.U(BRANCH_COMPARE_WIDTH.W) -> false.B,
    BRANCH_COMPARE_LT.U(BRANCH_COMPARE_WIDTH.W) -> io.LT,
    BRANCH_COMPARE_LTU.U(BRANCH_COMPARE_WIDTH.W) -> io.LTU
  ))
  BranchTaken := BranchFlag ^ io.Funct3(0)
  io.PCSrc := Jump || (Branch && BranchTaken)

  SubArith := ALUOp && (io.Funct3 === ALU_SLT.U(FUNCT3_WIDTH.W) ||
    io.Funct3 === ALU_SLTU.U(FUNCT3_WIDTH.W) ||
    (io.Funct3 === ALU_SRL_SRA.U(FUNCT3_WIDTH.W) && io.Funct7(5)) ||
    (io.Op === OP_REG.U(OPCODE_WIDTH.W) &&
      io.Funct3 === ALU_ADD_SUB.U(FUNCT3_WIDTH.W) && io.Funct7(5)))
  io.ALUControl := Cat(SubArith, ALUOp)
}

object Controller {
  private final case class ControlWord(
    regWrite: Boolean = false,
    immSrc: Int = IMM_I,
    aluSrc: Int = ALU_SRC_REG,
    aluOp: Boolean = false,
    aluResultSrc: Boolean = false,
    memRW: Int = MEM_NONE,
    resultSrc: Boolean = false,
    branch: Boolean = false,
    jump: Boolean = false
  ) {
    private def bit(value: Boolean): Int = if (value) 1 else 0
    val packed: Int = (bit(regWrite) << 12) | (immSrc << 9) | (aluSrc << 7) |
      (bit(aluOp) << 6) | (bit(aluResultSrc) << 5) | (memRW << 3) |
      (bit(resultSrc) << 2) | (bit(branch) << 1) | bit(jump)
  }
}
