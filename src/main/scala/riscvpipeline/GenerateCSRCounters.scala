package riscvpipeline

import chisel3.stage.ChiselStage
import riscvpipeline.privileged.CSRCounters

/** 参数：目录、S支持、活动HPM个数（0..29）。 */
object GenerateCSRCounters extends App {
  require(args.length <= 3, "Usage: GenerateCSRCounters [targetDir] [S] [HPM]")
  val targetDir = args.headOption.getOrElse("generated/csr-counters")
  val s = args.lift(1).map(_.toBoolean).getOrElse(true)
  val hpm = args.lift(2).map(_.toInt).getOrElse(0)
  (new ChiselStage).emitVerilog(new CSRCounters(s, hpm),
    Array("--target-dir", targetDir))
}
