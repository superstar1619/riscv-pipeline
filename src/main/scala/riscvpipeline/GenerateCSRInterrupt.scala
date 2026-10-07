package riscvpipeline

import chisel3.stage.ChiselStage
import riscvpipeline.privileged.CSRInterrupt

/** 生成固定RV32中断CSR；参数依次为目录、S支持、Sstc输入选择支持。 */
object GenerateCSRInterrupt extends App {
  require(args.length <= 3, "Usage: GenerateCSRInterrupt [targetDir] [S] [Sstc]")
  val targetDir = args.headOption.getOrElse("generated/csr-interrupt")
  val s = args.lift(1).map(_.toBoolean).getOrElse(true)
  val sstc = args.lift(2).map(_.toBoolean).getOrElse(false)
  (new ChiselStage).emitVerilog(new CSRInterrupt(s, sstc),
    Array("--target-dir", targetDir))
}
