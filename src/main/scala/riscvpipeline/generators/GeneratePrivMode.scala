package riscvpipeline.generators

import riscvpipeline._

import chisel3.stage.ChiselStage
import riscvpipeline.privileged.PrivMode

/** 参数：目录、S 支持；U/M 始终支持。 */
object GeneratePrivMode extends App {
  require(args.length <= 2, "Usage: GeneratePrivMode [targetDir] [S]")
  val targetDir = args.headOption.getOrElse("target/generated/priv-mode")
  val s = args.lift(1).map(_.toBoolean).getOrElse(true)
  (new ChiselStage).emitVerilog(new PrivMode(s), Array("--target-dir", targetDir))
}
