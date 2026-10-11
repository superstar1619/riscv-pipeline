package riscvpipeline.isa

/** 指令字段与完整指令的 Scala 编码；各使用位置显式指定硬件字面量位宽。 */
object InstructionEncoding {
  val OP_LOAD = 0x03
  val OP_MISC_MEM = 0x0f
  val OP_IMM = 0x13
  val OP_AUIPC = 0x17
  val OP_STORE = 0x23
  val OP_REG = 0x33
  val OP_LUI = 0x37
  val OP_BRANCH = 0x63
  val OP_JALR = 0x67
  val OP_JAL = 0x6f
  val OP_SYSTEM = 0x73

  val ALU_ADD_SUB = 0
  val ALU_SLL = 1
  val ALU_SLT = 2
  val ALU_SLTU = 3
  val ALU_XOR = 4
  val ALU_SRL_SRA = 5
  val ALU_OR = 6
  val ALU_AND = 7
  val FUNCT7_BASE = 0x00
  val FUNCT7_SUB_SRA = 0x20
  val JALR_FUNCT3 = 0

  val LOAD_BYTE = 0
  val LOAD_HALF = 1
  val LOAD_WORD = 2
  val LOAD_DOUBLE = 3
  val LOAD_BYTE_U = 4
  val LOAD_HALF_U = 5
  val LOAD_WORD_U = 6

  val STORE_BYTE = 0
  val STORE_HALF = 1
  val STORE_WORD = 2
  val STORE_DOUBLE = 3

  val BRANCH_EQ = 0
  val BRANCH_NE = 1
  val BRANCH_LT = 4
  val BRANCH_GE = 5
  val BRANCH_LTU = 6
  val BRANCH_GEU = 7
  // Funct3(2, 1) 选择比较种类，Funct3(0) 决定是否反转比较结果。
  val BRANCH_COMPARE_EQ = BRANCH_EQ >> 1
  val BRANCH_COMPARE_RESERVED = 1
  val BRANCH_COMPARE_LT = BRANCH_LT >> 1
  val BRANCH_COMPARE_LTU = BRANCH_LTU >> 1
  val BRANCH_COMPARE_WIDTH = Architecture.FUNCT3_WIDTH - 1

  val CSR_RW = 1
  val CSR_RS = 2
  val CSR_RC = 3
  val CSR_RWI = 5
  val CSR_RSI = 6
  val CSR_RCI = 7
  val FENCE = 0
  val FENCE_I = 1
  val FUNCT7_SFENCE_VMA = 0x09

  val INSTR_ECALL = BigInt("00000073", 16)
  val INSTR_EBREAK = BigInt("00100073", 16)
  val INSTR_MRET = BigInt("30200073", 16)
  val INSTR_SRET = BigInt("10200073", 16)
  val INSTR_WFI = BigInt("10500073", 16)
  val JALR_TARGET_MASK = Architecture.WORD_MASK ^ BigInt(1)

  val BYTE_ACCESS_BYTES = 1
  val HALFWORD_ACCESS_BYTES = 2
  val WORD_ACCESS_BYTES = 4
  val DOUBLEWORD_ACCESS_BYTES = 8
  val ENDIAN_BYTE_OFFSET_XOR = 3
  val ENDIAN_HALF_OFFSET_XOR = 2
}
