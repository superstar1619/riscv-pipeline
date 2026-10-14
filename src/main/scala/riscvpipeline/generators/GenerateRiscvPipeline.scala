package riscvpipeline.generators

import riscvpipeline._

import chisel3.stage.ChiselStage
import riscvpipeline.config.CpuConfig

/** 生成整机；参数依次为指令/数据字深度、输出目录、镜像文件（或 -）、复位字节地址。 */
object GenerateRiscvPipeline extends App {
  require(
    args.length <= 5,
    "Usage: GenerateRiscvPipeline [imemDepth] [dmemDepth] [targetDir] " +
      "[instructionInitFile|-] [resetVector]"
  )
  val imemDepth = args.headOption.map(_.toInt).getOrElse(CpuConfig.DEFAULT_IMEM_DEPTH)
  val dmemDepth = args.lift(1).map(_.toInt).getOrElse(CpuConfig.DEFAULT_DMEM_DEPTH)
  val targetDir = args.lift(2).getOrElse("target/generated/riscv-pipeline")
  val file = args.lift(3).getOrElse(CpuConfig.DEFAULT_PROGRAM_FILE)
  val resetVector = args.lift(4).map { value =>
    if (value.startsWith("0x") || value.startsWith("0X")) BigInt(value.drop(2), 16)
    else BigInt(value)
  }.getOrElse(CpuConfig.DEFAULT_RESET_VECTOR)
  val config = CpuConfig(imemDepth = imemDepth, dmemDepth = dmemDepth,
    resetVector = resetVector, instructionInitFile = if (file == "-") None else Some(file))
  (new ChiselStage).emitVerilog(
    new RiscvPipeline(config),
    Array("--target-dir", targetDir)
  )
}
