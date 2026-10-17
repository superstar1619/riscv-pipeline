package riscvpipeline.generators

import chisel3.stage.ChiselStage
import riscvpipeline.uncore.AHBAPBBridge

object GenerateAHBAPBBridge extends App {
  require(args.length <= 1, "Usage: GenerateAHBAPBBridge [targetDir]")
  (new ChiselStage).emitVerilog(new AHBAPBBridge,
    Array("--target-dir", args.headOption.getOrElse("target/generated/ahb-apb-bridge")))
}
