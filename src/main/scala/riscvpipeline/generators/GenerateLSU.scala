package riscvpipeline.generators

import riscvpipeline._

import chisel3.stage.ChiselStage
import riscvpipeline.config.CpuConfig
import riscvpipeline.lsu.LSU

/** 独立生成 LSU；可选参数依次为数据存储器字深度、输出目录。 */
object GenerateLSU extends App {
  require(args.length <= 2, "Usage: GenerateLSU [dmemDepth] [targetDir]")
  val depth = args.headOption.map(_.toInt).getOrElse(CpuConfig.DEFAULT_DMEM_DEPTH)
  val targetDir = args.lift(1).getOrElse("target/generated/lsu")
  (new ChiselStage).emitVerilog(
    new LSU(CpuConfig(dmemDepth = depth)),
    Array("--target-dir", targetDir)
  )
}
