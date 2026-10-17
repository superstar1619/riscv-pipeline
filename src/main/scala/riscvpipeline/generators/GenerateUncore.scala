package riscvpipeline.generators

import chisel3.stage.ChiselStage
import riscvpipeline.config.BusConfig
import riscvpipeline.uncore.Uncore

/** 参数：目标目录、统一额外等待周期、可选 BootROM hex32。 */
object GenerateUncore extends App {
  require(args.length <= 3, "Usage: GenerateUncore [targetDir] [extraLatency] [bootRomHex32]")
  val latency = args.lift(1).map(_.toInt).getOrElse(0)
  val config = BusConfig(bootRomInitFile = args.lift(2), ramLatency = latency,
    romLatency = latency, apbLatency = latency)
  (new ChiselStage).emitVerilog(new Uncore(config),
    Array("--target-dir", args.headOption.getOrElse("target/generated/uncore")))
}
