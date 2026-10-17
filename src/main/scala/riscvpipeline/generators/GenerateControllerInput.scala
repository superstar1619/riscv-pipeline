package riscvpipeline.generators

import chisel3.stage.ChiselStage
import riscvpipeline.ebu.ControllerInput

object GenerateControllerInput extends App {
  require(args.length <= 2, "Usage: GenerateControllerInput [targetDir] [saveEnabled]")
  val targetDir = args.headOption.getOrElse("target/generated/controller-input")
  val saveEnabled = args.lift(1).map(_.toBoolean).getOrElse(true)
  (new ChiselStage).emitVerilog(new ControllerInput(saveEnabled), Array("--target-dir", targetDir))
}
