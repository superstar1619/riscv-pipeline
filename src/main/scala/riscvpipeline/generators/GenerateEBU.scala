package riscvpipeline.generators

import chisel3.stage.ChiselStage
import riscvpipeline.ebu.EBU

object GenerateEBU extends App {
  require(args.length <= 1, "Usage: GenerateEBU [targetDir]")
  val targetDir = args.headOption.getOrElse("target/generated/ebu")
  (new ChiselStage).emitVerilog(new EBU, Array("--target-dir", targetDir))
}
