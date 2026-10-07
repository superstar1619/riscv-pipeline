package riscvpipeline

import chisel3.stage.ChiselStage
import riscvpipeline.privileged.CSRMachine

/** 参数：目录、S、VM、Sstc、向量支持、HPM个数、hartId（十进制或0x十六进制）。 */
object GenerateCSRMachine extends App {
  require(args.length <= 7,
    "Usage: GenerateCSRMachine [targetDir] [S] [VM] [Sstc] [vector] [HPM] [hartId]")
  val targetDir = args.headOption.getOrElse("generated/csr-machine")
  val s = args.lift(1).map(_.toBoolean).getOrElse(true)
  val vm = args.lift(2).map(_.toBoolean).getOrElse(false)
  val stc = args.lift(3).map(_.toBoolean).getOrElse(false)
  val vector = args.lift(4).map(_.toBoolean).getOrElse(true)
  val hpm = args.lift(5).map(_.toInt).getOrElse(0)
  val hartText = args.lift(6).getOrElse("0")
  val hart = if (hartText.startsWith("0x") || hartText.startsWith("0X")) {
    BigInt(hartText.drop(2), 16)
  } else {
    BigInt(hartText)
  }
  (new ChiselStage).emitVerilog(new CSRMachine(s, vm, stc, vector, hpm, hart),
    Array("--target-dir", targetDir))
}
