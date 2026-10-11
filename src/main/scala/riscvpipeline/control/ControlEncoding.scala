package riscvpipeline.control

/** 数据通路控制的 Scala 编码；不保存跨模块使用的硬件字面量。 */
object ControlEncoding {
  val MEM_WIDTH = 2
  val MEM_NONE = 0
  val MEM_WRITE = 1
  val MEM_READ = 2
  val MEM_WRITE_BIT = 0
  val MEM_READ_BIT = 1

  val FORWARD_WIDTH = 2
  val FORWARD_NONE = 0
  val FORWARD_W = 1
  val FORWARD_M = 2

  val IMM_SOURCE_WIDTH = 3
  val IMM_I = 0
  val IMM_S = 1
  val IMM_B = 2
  val IMM_J = 3
  val IMM_U = 4

  val ALU_SOURCE_WIDTH = 2
  val ALU_SRC_REG = 0
  val ALU_SRC_IMM = 1
  val ALU_SRC_PC_IMM = 3
  val ALU_SRC_IMM_BIT = 0
  val ALU_SRC_PC_BIT = 1

  val ALU_CONTROL_WIDTH = 2
  val ALU_OP_BIT = 0
  val ALU_SUB_ARITH_BIT = 1
}
