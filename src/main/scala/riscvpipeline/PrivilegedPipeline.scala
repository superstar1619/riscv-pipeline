package riscvpipeline

import chisel3._
import riscvpipeline.config.CpuConfig
import riscvpipeline.privileged.InterruptInputs

/** 第八章顶层：同域中断/time输入，既有M级观察输出不变。 */
final class PrivilegedPipeline(
  val config: CpuConfig = CpuConfig(privilegedSupported = true)
) extends PipelineBase(config) {
  require(config.privilegedSupported, "PrivilegedPipeline requires privileged support")
  val interrupts = IO(Input(new InterruptInputs))
  Interrupts := interrupts
}
