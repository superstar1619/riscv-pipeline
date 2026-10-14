package riscvpipeline.generators

import riscvpipeline._

import chisel3.stage.ChiselStage
import riscvpipeline.config.CpuConfig
import riscvpipeline.privileged.PrivilegedConfig

/** 参数：指令/数据字深度、目录、镜像、复位地址、S、大端、Sstc、HPM、WFI阈值。 */
object GeneratePrivilegedPipeline extends App {
  require(args.length <= 10,
    "Usage: GeneratePrivilegedPipeline [imem] [dmem] [dir] [file|-] " +
      "[resetVector] [S] [BE] [Sstc] [HPM] [WFI]")
  val file = args.lift(3).getOrElse(CpuConfig.DEFAULT_PROGRAM_FILE)
  val resetVector = args.lift(4).map { value =>
    if (value.startsWith("0x") || value.startsWith("0X")) BigInt(value.drop(2), 16)
    else BigInt(value)
  }.getOrElse(CpuConfig.DEFAULT_RESET_VECTOR)
  val defaults = PrivilegedConfig()
  val config = CpuConfig(
    imemDepth = args.headOption.map(_.toInt).getOrElse(CpuConfig.DEFAULT_IMEM_DEPTH),
    dmemDepth = args.lift(1).map(_.toInt).getOrElse(CpuConfig.DEFAULT_DMEM_DEPTH),
    instructionInitFile = if (file == "-") None else Some(file),
    resetVector = resetVector,
    privilegedSupported = true,
    privileged = PrivilegedConfig(
      supervisorSupported =
        args.lift(5).map(_.toBoolean).getOrElse(defaults.supervisorSupported),
      bigEndianSupported =
        args.lift(6).map(_.toBoolean).getOrElse(defaults.bigEndianSupported),
      sstcSupported = args.lift(7).map(_.toBoolean).getOrElse(defaults.sstcSupported),
      hpmCounters = args.lift(8).map(_.toInt).getOrElse(defaults.hpmCounters),
      wfiTimeoutCycles = args.lift(9).map(_.toInt).getOrElse(defaults.wfiTimeoutCycles)))
  (new ChiselStage).emitVerilog(new PrivilegedPipeline(config),
    Array("--target-dir", args.lift(2).getOrElse("target/generated/privileged-pipeline")))
}
