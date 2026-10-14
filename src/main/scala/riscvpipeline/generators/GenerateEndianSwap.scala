package riscvpipeline.generators

import riscvpipeline._

import chisel3.stage.ChiselStage
import riscvpipeline.privileged.EndianSwap

/** 参数：目标目录；数据字宽固定 32 位。 */
object GenerateEndianSwap extends App {
  require(args.length <= 1, "Usage: GenerateEndianSwap [targetDir]")
  val targetDir = args.headOption.getOrElse("target/generated/endian-swap")
  (new ChiselStage).emitVerilog(new EndianSwap, Array("--target-dir", targetDir))
}
