package riscvpipeline.generators

import chisel3.stage.ChiselStage
import riscvpipeline.hazard.BusHazardControl

object GenerateBusHazardControl extends App {
  require(args.length <= 1, "Usage: GenerateBusHazardControl [targetDir]")
  val target = args.headOption.getOrElse("target/generated/bus-hazard")
  (new ChiselStage).emitVerilog(new BusHazardControl, Array("--target-dir", target))
}
