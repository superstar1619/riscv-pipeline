package riscvpipeline.generators

import riscvpipeline._

import chisel3.stage.ChiselStage
import riscvpipeline.privileged.PrivDecode

/** 独立生成 RV32 PrivDecode；参数依次为目录、S 支持、虚拟内存译码支持。 */
object GeneratePrivDecode extends App {
  require(args.length <= 3,
    "Usage: GeneratePrivDecode [targetDir] [supervisorSupported] [virtualMemorySupported]")
  val targetDir = args.headOption.getOrElse("target/generated/priv-decode")
  val supervisorSupported = args.lift(1).map(_.toBoolean).getOrElse(true)
  val virtualMemorySupported = args.lift(2).map(_.toBoolean).getOrElse(false)
  (new ChiselStage).emitVerilog(new PrivDecode(supervisorSupported, virtualMemorySupported),
    Array("--target-dir", targetDir))
}
