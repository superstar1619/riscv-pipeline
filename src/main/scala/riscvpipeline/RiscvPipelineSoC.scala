package riscvpipeline

import chisel3._
import riscvpipeline.config.{BusConfig, CpuConfig}
import riscvpipeline.privileged.InterruptInputs
import riscvpipeline.uncore.Uncore

object RiscvPipelineSoC {
  def defaultConfig: CpuConfig = CpuConfig(privilegedSupported = true,
    resetVector = 0x1000, bus = Some(BusConfig()))
}

/** 第九章单时钟 SoC：BusPipeline 与 BootROM/RAM/APB Uncore。
  * 保留六个 M 级架构观察输出；interrupts/time 与内核同域。
  * 显式 clk/reset；下层状态统一使用高有效同步复位。
  */
final class RiscvPipelineSoC(
  val config: CpuConfig = RiscvPipelineSoC.defaultConfig
) extends RawModule {
  require(config.bus.nonEmpty, "RiscvPipelineSoC requires BusConfig")
  require(config.privilegedSupported, "RiscvPipelineSoC requires privileged support")
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new RiscvPipelineIO(config))
  val interrupts = IO(Input(new InterruptInputs))
  val core = Module(new BusPipeline(config))
  val uncore = Module(new Uncore(config.bus.get))
  core.clk := clk
  core.reset := reset
  core.interrupts := interrupts
  uncore.clk := clk
  uncore.reset := reset
  uncore.io.ahb <> core.ahb
  io <> core.io
}
