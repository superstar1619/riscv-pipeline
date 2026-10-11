package riscvpipeline

import chisel3.stage.ChiselStage
import riscvpipeline.privileged.{Privileged, PrivilegedConfig}

/** 参数：目录、S、大端、Sstc、HPM个数、WFI等待阈值。 */
object GeneratePrivileged extends App {
  require(args.length <= 6, "Usage: GeneratePrivileged [dir] [S] [BE] [Sstc] [HPM] [WFI]")
  val targetDir = args.headOption.getOrElse("generated/privileged")
  val defaults = PrivilegedConfig()
  val config = PrivilegedConfig(
    supervisorSupported =
      args.lift(1).map(_.toBoolean).getOrElse(defaults.supervisorSupported),
    bigEndianSupported = args.lift(2).map(_.toBoolean).getOrElse(defaults.bigEndianSupported),
    sstcSupported = args.lift(3).map(_.toBoolean).getOrElse(defaults.sstcSupported),
    hpmCounters = args.lift(4).map(_.toInt).getOrElse(defaults.hpmCounters),
    wfiTimeoutCycles = args.lift(5).map(_.toInt).getOrElse(defaults.wfiTimeoutCycles))
  (new ChiselStage).emitVerilog(new Privileged(config), Array("--target-dir", targetDir))
}
