package riscvpipeline

import chisel3.stage.ChiselStage
import riscvpipeline.privileged.Trap

/** 独立生成 RV32 Trap；参数依次为目录、S 支持。 */
object GenerateTrap extends App {
  require(args.length <= 2, "Usage: GenerateTrap [targetDir] [supervisorSupported]")
  val targetDir = args.headOption.getOrElse("generated/trap")
  val supervisorSupported = args.lift(1).map(_.toBoolean).getOrElse(true)
  (new ChiselStage).emitVerilog(new Trap(supervisorSupported),
    Array("--target-dir", targetDir))
}
