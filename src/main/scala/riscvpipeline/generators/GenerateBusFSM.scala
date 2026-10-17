package riscvpipeline.generators

import chisel3.stage.ChiselStage
import riscvpipeline.ebu.BusFSM

/** 参数：目标目录、readOnly（false 为 LSU，true 为 IFU）。 */
object GenerateBusFSM extends App {
  require(args.length <= 2, "Usage: GenerateBusFSM [targetDir] [readOnly]")
  val targetDir = args.headOption.getOrElse("target/generated/bus-fsm")
  val readOnly = args.lift(1).map(_.toBoolean).getOrElse(false)
  (new ChiselStage).emitVerilog(new BusFSM(readOnly), Array("--target-dir", targetDir))
}
