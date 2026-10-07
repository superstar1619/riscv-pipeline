package riscvpipeline

import chisel3.stage.ChiselStage
import riscvpipeline.privileged.CSRSupervisor

/** 参数：目录、S、VM、Sstc、向量支持、HPM个数、Sv32 ASID位数。 */
object GenerateCSRSupervisor extends App {
  require(args.length <= 7,
    "Usage: GenerateCSRSupervisor [targetDir] [S] [VM] [Sstc] [vector] [HPM] [ASID]")
  val targetDir = args.headOption.getOrElse("generated/csr-supervisor")
  val s = args.lift(1).map(_.toBoolean).getOrElse(true)
  val vm = args.lift(2).map(_.toBoolean).getOrElse(false)
  val stc = args.lift(3).map(_.toBoolean).getOrElse(false)
  val vector = args.lift(4).map(_.toBoolean).getOrElse(true)
  val hpm = args.lift(5).map(_.toInt).getOrElse(0)
  val asid = args.lift(6).map(_.toInt).getOrElse(0)
  (new ChiselStage).emitVerilog(new CSRSupervisor(s, vm, stc, vector, hpm, asid),
    Array("--target-dir", targetDir))
}
