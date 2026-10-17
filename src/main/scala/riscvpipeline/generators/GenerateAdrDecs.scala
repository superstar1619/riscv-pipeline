package riscvpipeline.generators

import chisel3.stage.ChiselStage
import riscvpipeline.uncore.AdrDecs

object GenerateAdrDecs extends App {
  require(args.length <= 1, "Usage: GenerateAdrDecs [targetDir]")
  (new ChiselStage).emitVerilog(new AdrDecs,
    Array("--target-dir", args.headOption.getOrElse("target/generated/adr-decs")))
}
