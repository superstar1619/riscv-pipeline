package riscvpipeline

import chisel3.stage.ChiselStage
import riscvpipeline.privileged.{CSR, PrivilegedConfig}

/** 参数：目录、S支持、大端支持、Sstc支持、活动HPM个数（0..29）。 */
object GenerateCSR extends App {
  require(args.length <= 5, "Usage: GenerateCSR [targetDir] [S] [BigEndian] [Sstc] [HPM]")
  val targetDir = args.headOption.getOrElse("generated/csr")
  val config = PrivilegedConfig(
    supervisorSupported = args.lift(1).map(_.toBoolean).getOrElse(true),
    bigEndianSupported = args.lift(2).map(_.toBoolean).getOrElse(true),
    sstcSupported = args.lift(3).map(_.toBoolean).getOrElse(false),
    hpmCounters = args.lift(4).map(_.toInt).getOrElse(0))
  (new ChiselStage).emitVerilog(new CSR(config), Array("--target-dir", targetDir))
}
