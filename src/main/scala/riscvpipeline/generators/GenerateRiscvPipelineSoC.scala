package riscvpipeline.generators

import chisel3.stage.ChiselStage
import riscvpipeline.RiscvPipelineSoC
import riscvpipeline.config.{BusConfig, CpuConfig}

/** 参数：目录、镜像或 -、bus/mixed、RAM 等待、APB 等待。 */
object GenerateRiscvPipelineSoC extends App {
  require(args.length <= 5,
    "Usage: GenerateRiscvPipelineSoC [targetDir] [image|-] [bus|mixed] [ramWait] [apbWait]")
  val targetDir = args.headOption.getOrElse("target/generated/riscv-pipeline-soc")
  val image = args.lift(1).filter(_ != "-")
  val mode = args.lift(2).getOrElse("bus")
  require(mode == "bus" || mode == "mixed", "mode must be bus or mixed")
  val mixed = mode == "mixed"
  val bus = BusConfig(bootRomInitFile = image,
    ramLatency = args.lift(3).map(_.toInt).getOrElse(0),
    apbLatency = args.lift(4).map(_.toInt).getOrElse(0),
    iromSupported = mixed, dtimSupported = mixed)
  val config = CpuConfig(imemDepth = 1024, dmemDepth = 1024,
    resetVector = if (mixed) 0 else 0x1000, instructionInitFile = image,
    privilegedSupported = true, bus = Some(bus))
  (new ChiselStage).emitVerilog(new RiscvPipelineSoC(config), Array("--target-dir", targetDir))
}
