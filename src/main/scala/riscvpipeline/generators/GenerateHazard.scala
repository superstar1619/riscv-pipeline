package riscvpipeline.generators

import chisel3.stage.ChiselStage
import riscvpipeline.hazard.Hazard

object GenerateHazard extends App {
  require(args.length <= 1, "Usage: GenerateHazard [targetDir]")
  val target = args.headOption.getOrElse("target/generated/hazard")
  (new ChiselStage).emitVerilog(new Hazard, Array("--target-dir", target))
}
