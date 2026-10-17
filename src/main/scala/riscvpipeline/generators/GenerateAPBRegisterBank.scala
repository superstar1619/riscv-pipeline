package riscvpipeline.generators

import chisel3.stage.ChiselStage
import riscvpipeline.uncore.APBRegisterBank

object GenerateAPBRegisterBank extends App {
  require(args.length <= 2, "Usage: GenerateAPBRegisterBank [targetDir] [extraLatency]")
  (new ChiselStage).emitVerilog(new APBRegisterBank(
    args.lift(1).map(_.toInt).getOrElse(0)),
    Array("--target-dir", args.headOption.getOrElse("target/generated/apb-register-bank")))
}
