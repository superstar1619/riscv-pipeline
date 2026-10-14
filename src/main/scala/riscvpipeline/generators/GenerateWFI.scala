package riscvpipeline.generators

import riscvpipeline._

import chisel3.stage.ChiselStage
import riscvpipeline.privileged.WFI

/** 参数：目标目录、等待上升沿阈值（1..2^24）。 */
object GenerateWFI extends App {
  require(args.length <= 2, "Usage: GenerateWFI [targetDir] [timeoutCycles]")
  val targetDir = args.headOption.getOrElse("target/generated/wfi")
  val timeoutCycles = args.lift(1).map(_.toInt).getOrElse(WFI.DEFAULT_TIMEOUT_CYCLES)
  (new ChiselStage).emitVerilog(new WFI(timeoutCycles), Array("--target-dir", targetDir))
}
