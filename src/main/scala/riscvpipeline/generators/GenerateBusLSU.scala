package riscvpipeline.generators

import chisel3.stage.ChiselStage
import riscvpipeline.config.{BusConfig, CpuConfig}
import riscvpipeline.lsu.BusLSU

object GenerateBusLSU extends App {
  require(args.length <= 2, "Usage: GenerateBusLSU [targetDir] [dtimSupported]")
  val targetDir = args.headOption.getOrElse("target/generated/bus-lsu")
  val local = args.lift(1).map(_.toBoolean).getOrElse(false)
  val config = CpuConfig(privilegedSupported = true, dmemDepth = 1024,
    bus = Some(BusConfig(dtimSupported = local)))
  (new ChiselStage).emitVerilog(new BusLSU(config), Array("--target-dir", targetDir))
}
