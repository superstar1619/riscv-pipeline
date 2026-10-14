package riscvpipeline.generators

import riscvpipeline._

import chisel3.stage.ChiselStage
import riscvpipeline.privileged.CSRStatus

/** 生成固定 RV32 CSRStatus；参数依次为目录、S、VM、大端支持。 */
object GenerateCSRStatus extends App {
  require(args.length <= 4, "Usage: GenerateCSRStatus [targetDir] [S] [VM] [bigEndian]")
  val targetDir = args.headOption.getOrElse("target/generated/csr-status")
  val s = args.lift(1).map(_.toBoolean).getOrElse(true)
  val vm = args.lift(2).map(_.toBoolean).getOrElse(false)
  val be = args.lift(3).map(_.toBoolean).getOrElse(false)
  (new ChiselStage).emitVerilog(new CSRStatus(s, vm, be),
    Array("--target-dir", targetDir))
}
