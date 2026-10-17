package riscvpipeline.generators

import chisel3.stage.ChiselStage
import riscvpipeline.uncore.RAMAHB

/** 参数：目标目录、额外等待周期。 */
object GenerateRAMAHB extends App {
  require(args.length <= 2, "Usage: GenerateRAMAHB [targetDir] [extraLatency]")
  (new ChiselStage).emitVerilog(new RAMAHB(extraLatency =
    args.lift(1).map(_.toInt).getOrElse(0)),
    Array("--target-dir", args.headOption.getOrElse("target/generated/ram-ahb")))
}
