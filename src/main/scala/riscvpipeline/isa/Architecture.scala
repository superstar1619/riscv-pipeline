package riscvpipeline.isa

/** 固定 RV32 架构尺寸；所有值是 Scala 常量，在模块使用处转换为硬件字面量。 */
object Architecture {
  final val XLEN = 32
  final val BYTE_WIDTH = 8
  final val BYTES_PER_WORD = XLEN / BYTE_WIDTH
  final val WORD_ALIGNMENT_BITS = 2
  final val INSTRUCTION_WIDTH = 32
  final val INSTRUCTION_BYTES = INSTRUCTION_WIDTH / BYTE_WIDTH
  final val INSTRUCTION_ALIGNMENT_BITS = 2
  final val REGISTER_COUNT = 32
  final val REGISTER_INDEX_WIDTH = 5
  final val OPCODE_WIDTH = 7
  final val FUNCT3_WIDTH = 3
  final val FUNCT7_WIDTH = 7
  final val WORD_MASK: BigInt = (BigInt(1) << XLEN) - 1
}
