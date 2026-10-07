package riscvpipeline

import chisel3.stage.ChiselStage
import riscvpipeline.privileged.CSRAccess

/** 独立生成固定 RV32 CSRAccess；可选参数为输出目录。 */
object GenerateCSRAccess extends App {
  require(args.length <= 1, "Usage: GenerateCSRAccess [targetDir]")
  val targetDir = args.headOption.getOrElse("generated/csr-access")
  (new ChiselStage).emitVerilog(new CSRAccess, Array("--target-dir", targetDir))
}
