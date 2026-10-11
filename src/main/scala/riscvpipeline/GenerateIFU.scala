package riscvpipeline

import chisel3.stage.ChiselStage
import riscvpipeline.config.CpuConfig
import riscvpipeline.ifu.IFU

/** 独立生成 IFU；参数依次为字深度、输出目录、镜像文件（或 -）、复位字节地址。 */
object GenerateIFU extends App {
  require(args.length <= 4,
    "Usage: GenerateIFU [imemDepth] [targetDir] [instructionInitFile|-] [resetVector]")
  val depth = args.headOption.map(_.toInt).getOrElse(CpuConfig.DEFAULT_IMEM_DEPTH)
  val targetDir = args.lift(1).getOrElse("generated/ifu")
  val file = args.lift(2).getOrElse(CpuConfig.DEFAULT_PROGRAM_FILE)
  val resetVector = args.lift(3).map { value =>
    if (value.startsWith("0x") || value.startsWith("0X")) BigInt(value.drop(2), 16)
    else BigInt(value)
  }.getOrElse(CpuConfig.DEFAULT_RESET_VECTOR)
  val config = CpuConfig(imemDepth = depth, resetVector = resetVector,
    instructionInitFile = if (file == "-") None else Some(file))
  (new ChiselStage).emitVerilog(
    new IFU(config),
    Array("--target-dir", targetDir)
  )
}
