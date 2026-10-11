package riscvpipeline.config

import riscvpipeline.privileged.PrivilegedConfig
import riscvpipeline.isa.Architecture._

object CpuConfig {
  final val DEFAULT_IMEM_DEPTH = 64
  final val DEFAULT_DMEM_DEPTH = 64
  final val DEFAULT_RESET_VECTOR: BigInt = 0
  // 独立生成入口默认使用教材镜像；直接构造 CPU 时仍可不指定镜像。
  final val DEFAULT_PROGRAM_FILE = "programs/riscvtest.memfile"
}

/** 教材 RV32 五级流水线 CPU 的不可变生成阶段配置，由顶层传递给各单元。
  * imemDepth/dmemDepth 以 32 位字计，resetVector 是字节地址；仅支持 XLEN=32。
  * 配置只决定生成结构、初始镜像与复位值，不是硬件运行时信号。
  */
final case class CpuConfig(
  xlen: Int = XLEN,
  imemDepth: Int = CpuConfig.DEFAULT_IMEM_DEPTH,
  dmemDepth: Int = CpuConfig.DEFAULT_DMEM_DEPTH,
  resetVector: BigInt = CpuConfig.DEFAULT_RESET_VECTOR,
  instructionInitFile: Option[String] = None,
  privilegedSupported: Boolean = false,
  privileged: PrivilegedConfig = PrivilegedConfig()
) {
  require(xlen == XLEN, "The first CPU implementation supports only XLEN=32")

  private def validDepth(depth: Int): Boolean =
    depth >= 2 && (depth & (depth - 1)) == 0 &&
      BigInt(depth) * BYTES_PER_WORD <= (BigInt(1) << xlen)

  require(
    validDepth(imemDepth),
    "imemDepth must be a power of two >= 2 fitting the address space"
  )
  require(
    validDepth(dmemDepth),
    "dmemDepth must be a power of two >= 2 fitting the address space"
  )
  require(resetVector >= 0 && resetVector < (BigInt(1) << xlen), "resetVector must fit XLEN")
  require(resetVector % INSTRUCTION_BYTES == 0,
    "resetVector must be aligned to a 4-byte instruction")
  require(instructionInitFile.forall(_.trim.nonEmpty), "instructionInitFile must not be blank")
}
