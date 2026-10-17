package riscvpipeline.generators

import chisel3.stage.ChiselStage
import riscvpipeline.config.{BusConfig, CpuConfig}
import riscvpipeline.ifu.BusIFU

object GenerateBusIFU extends App {
  require(args.length <= 2, "Usage: GenerateBusIFU [targetDir] [iromSupported]")
  val targetDir = args.headOption.getOrElse("target/generated/bus-ifu")
  val local = args.lift(1).map(_.toBoolean).getOrElse(false)
  val config = CpuConfig(privilegedSupported = true, imemDepth = 1024,
    resetVector = if (local) 0 else 0x1000,
    bus = Some(BusConfig(iromSupported = local)))
  (new ChiselStage).emitVerilog(new BusIFU(config), Array("--target-dir", targetDir))
}
