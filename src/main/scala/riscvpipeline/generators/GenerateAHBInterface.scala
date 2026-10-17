package riscvpipeline.generators

import chisel3.stage.ChiselStage
import riscvpipeline.ebu.AHBInterface

object GenerateAHBInterface extends App {
  require(args.length <= 2, "Usage: GenerateAHBInterface [targetDir] [readOnly]")
  val targetDir = args.headOption.getOrElse("target/generated/ahb-interface")
  val readOnly = args.lift(1).map(_.toBoolean).getOrElse(false)
  (new ChiselStage).emitVerilog(new AHBInterface(readOnly), Array("--target-dir", targetDir))
}
