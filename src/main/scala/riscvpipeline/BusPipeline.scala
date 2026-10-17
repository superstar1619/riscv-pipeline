package riscvpipeline

import chisel3._
import riscvpipeline.config.{BusConfig, CpuConfig}
import riscvpipeline.ebu.AHBMasterIO
import riscvpipeline.privileged.InterruptInputs

/** 第九章外部 AHB 核心，保留原 M 级观察接口。 */
final class BusPipeline(val config: CpuConfig = CpuConfig(
  resetVector = 0x1000, privilegedSupported = true, bus = Some(BusConfig())
)) extends PipelineBase(config) {
  require(config.bus.nonEmpty && config.privilegedSupported,
    "BusPipeline requires bus and privileged support")
  val interrupts = IO(Input(new InterruptInputs))
  val ahb = IO(new AHBMasterIO)
  Interrupts := interrupts
  ahb <> ExternalBus.get
}
