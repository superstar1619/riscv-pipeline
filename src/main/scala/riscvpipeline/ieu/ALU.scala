package riscvpipeline.ieu

import chisel3._
import chisel3.util.{Cat, Fill, MuxLookup, log2Ceil}
import riscvpipeline.control.ControlEncoding._
import riscvpipeline.isa.Architecture._
import riscvpipeline.isa.InstructionEncoding._

/** 教材兼容的操作数与控制接口；IEUAdr 独立保留加减结果，供字节地址生成使用。 */
final class ALUIO(dataWidth: Int) extends Bundle {
  val SrcA = Input(UInt(dataWidth.W))
  val SrcB = Input(UInt(dataWidth.W))
  val ALUControl = Input(UInt(ALU_CONTROL_WIDTH.W))
  val Funct3 = Input(UInt(FUNCT3_WIDTH.W))
  val ALUResult = Output(UInt(dataWidth.W))
  val IEUAdr = Output(UInt(dataWidth.W))
}

/** 教材第 7 章 §7.1.3.1/.2/.4，pp. 304–308：整数 ALU。
  * 纯组合，无时钟、复位或状态；结果按 dataWidth 回绕，进位用于无符号比较。
  * ALUControl = {SubArith, ALUOp}；减法、大小比较及算术右移由控制器使能 SubArith。
  */
final class ALU(val dataWidth: Int = XLEN) extends RawModule {
  require(dataWidth > 0, "ALU dataWidth must be > 0")

  val io = IO(new ALUIO(dataWidth))

  val ALUOp = Wire(Bool())
  val SubArith = Wire(Bool())
  val CondInvB = Wire(UInt(dataWidth.W)).suggestName("CondInvb")
  val SumExt = Wire(UInt((dataWidth + 1).W))
  val Carry = Wire(Bool())
  val Sum = Wire(UInt(dataWidth.W))
  val Overflow = Wire(Bool())
  val Neg = Wire(Bool())
  val LT = Wire(Bool())
  val LTU = Wire(Bool())
  val SLT = Wire(UInt(dataWidth.W))
  val SLTU = Wire(UInt(dataWidth.W))
  val ShiftResult = Wire(UInt(dataWidth.W))
  val ALUSelect = Wire(UInt(FUNCT3_WIDTH.W))

  ALUOp := io.ALUControl(ALU_OP_BIT)
  SubArith := io.ALUControl(ALU_SUB_ARITH_BIT)
  CondInvB := Mux(SubArith, ~io.SrcB, io.SrcB)
  SumExt := (io.SrcA +& CondInvB) + SubArith.asUInt
  Carry := SumExt(dataWidth)
  Sum := SumExt(dataWidth - 1, 0)
  io.IEUAdr := Sum

  // 教材减法溢出修正：即使截断差值的符号因溢出而反转，也保持有符号 slt 的语义。
  Overflow := (io.SrcA(dataWidth - 1) ^ io.SrcB(dataWidth - 1)) &
    (io.SrcA(dataWidth - 1) ^ Sum(dataWidth - 1))
  Neg := Sum(dataWidth - 1)
  LT := Neg ^ Overflow
  LTU := !Carry
  SLT := Mux(LT, 1.U(dataWidth.W), 0.U(dataWidth.W))
  SLTU := Mux(LTU, 1.U(dataWidth.W), 0.U(dataWidth.W))

  // 保留 ALU 的任意正字宽契约，补齐到二次幂后复用 Shifter；低 dataWidth 位结果不变。
  private val shiftWidth = 1 << math.max(1, log2Ceil(dataWidth))
  val shifter = Module(new Shifter(shiftWidth))
  shifter.io.A := (if (shiftWidth == dataWidth) io.SrcA
    else Cat(Fill(shiftWidth - dataWidth, io.SrcA(dataWidth - 1) && SubArith), io.SrcA))
  shifter.io.Amt := (if (dataWidth == 1) 0.U(1.W)
    else io.SrcB(log2Ceil(dataWidth) - 1, 0))
  shifter.io.Right := io.Funct3(2)
  shifter.io.SubArith := SubArith
  ShiftResult := shifter.io.Y(dataWidth - 1, 0)

  // 访存、分支和 jal 由控制器将 ALUOp/SubArith 置零，强制加法，不依赖 Funct3。
  ALUSelect := io.Funct3 & Fill(FUNCT3_WIDTH, ALUOp)
  io.ALUResult := MuxLookup(ALUSelect, 0.U(dataWidth.W))(Seq(
    ALU_ADD_SUB.U(FUNCT3_WIDTH.W) -> Sum,
    ALU_SLL.U(FUNCT3_WIDTH.W) -> ShiftResult,
    ALU_SLT.U(FUNCT3_WIDTH.W) -> SLT,
    ALU_SLTU.U(FUNCT3_WIDTH.W) -> SLTU,
    ALU_XOR.U(FUNCT3_WIDTH.W) -> (io.SrcA ^ io.SrcB),
    ALU_SRL_SRA.U(FUNCT3_WIDTH.W) -> ShiftResult,
    ALU_OR.U(FUNCT3_WIDTH.W) -> (io.SrcA | io.SrcB),
    ALU_AND.U(FUNCT3_WIDTH.W) -> (io.SrcA & io.SrcB)
  ))
}
