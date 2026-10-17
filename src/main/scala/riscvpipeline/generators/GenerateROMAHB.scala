package riscvpipeline.generators

import chisel3.stage.ChiselStage
import riscvpipeline.uncore.ROMAHB

object GenerateROMAHB extends App {
  require(args.length <= 3, "Usage: GenerateROMAHB [targetDir] [extraLatency] [initHex32]")
  (new ChiselStage).emitVerilog(new ROMAHB(initFile = args.lift(2),
    extraLatency = args.lift(1).map(_.toInt).getOrElse(0)),
    Array("--target-dir", args.headOption.getOrElse("target/generated/rom-ahb")))
}
