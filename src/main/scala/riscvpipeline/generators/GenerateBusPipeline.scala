package riscvpipeline.generators

import chisel3.stage.ChiselStage
import riscvpipeline.BusPipeline

object GenerateBusPipeline extends App {
  val target = args.headOption.getOrElse("target/generated/bus-pipeline")
  (new ChiselStage).emitVerilog(new BusPipeline, Array("--target-dir", target))
}
