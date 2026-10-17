package riscvpipeline.generators

import chisel3.stage.ChiselStage
import riscvpipeline.ebu.EBUFSMArb

object GenerateEBUFSMArb extends App {
  require(args.length <= 1, "Usage: GenerateEBUFSMArb [targetDir]")
  val targetDir = args.headOption.getOrElse("target/generated/ebu-fsm-arb")
  (new ChiselStage).emitVerilog(new EBUFSMArb, Array("--target-dir", targetDir))
}
