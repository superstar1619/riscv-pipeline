package riscvpipeline.isa

import chisel3._
import riscvpipeline.isa.Architecture._
import riscvpipeline.isa.InstructionEncoding._

/** 复用的组合识别逻辑；每次调用构造当前模块的硬件，不缓存 Chisel Data。 */
object InstructionDecode {
  def isCSR(instr: UInt): Bool =
    instr(6, 0) === OP_SYSTEM.U(OPCODE_WIDTH.W) &&
      Seq(CSR_RW, CSR_RS, CSR_RC, CSR_RWI, CSR_RSI, CSR_RCI)
        .map(code => instr(14, 12) === code.U(FUNCT3_WIDTH.W)).reduce(_ || _)

  def isFence(instr: UInt): Bool =
    instr(6, 0) === OP_MISC_MEM.U(OPCODE_WIDTH.W) &&
      (instr(14, 12) === FENCE.U(FUNCT3_WIDTH.W) ||
        instr(14, 12) === FENCE_I.U(FUNCT3_WIDTH.W))
}
