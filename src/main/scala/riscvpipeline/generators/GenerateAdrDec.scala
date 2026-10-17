package riscvpipeline.generators

import chisel3.stage.ChiselStage
import riscvpipeline.uncore.AdrDec

object GenerateAdrDec extends App {
  require(args.length <= 1, "Usage: GenerateAdrDec [targetDir]")
  (new ChiselStage).emitVerilog(new AdrDec,
    Array("--target-dir", args.headOption.getOrElse("target/generated/adr-dec")))
}
