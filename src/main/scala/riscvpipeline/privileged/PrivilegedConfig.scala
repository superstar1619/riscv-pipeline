package riscvpipeline.privileged

import chisel3._
import riscvpipeline.isa.Architecture.XLEN
import riscvpipeline.privileged.PrivilegedConstants.Counter

/** 第八章公共配置；固定RV32、U/M与Bare地址空间，S和相关扩展可选。 */
final case class PrivilegedConfig(
  supervisorSupported: Boolean = true,
  bigEndianSupported: Boolean = true,
  sstcSupported: Boolean = false,
  vectoredInterruptsSupported: Boolean = true,
  hpmCounters: Int = 0,
  hartId: BigInt = 0,
  wfiTimeoutCycles: Int = WFI.DEFAULT_TIMEOUT_CYCLES
) {
  require(!sstcSupported || supervisorSupported, "Sstc requires supervisor support")
  require(hpmCounters >= 0 && hpmCounters <= Counter.HPM_COUNT,
    "HPM counter count must be 0..29")
  require(hartId >= 0 && hartId < (BigInt(1) << XLEN), "hartId must fit unsigned RV32")
  require(wfiTimeoutCycles >= 1 && wfiTimeoutCycles <= WFI.MAX_TIMEOUT_CYCLES,
    "WFI timeout must be 1..2^24 cycles")
}

/** 所有中断电平须已同步；TIME_REGW须是clk同域一致的64位时间值。 */
final class InterruptInputs extends Bundle {
  val MExtInt = Bool()
  val SExtInt = Bool()
  val MTimerInt = Bool()
  val MSwInt = Bool()
  val TIME_REGW = UInt(Counter.WIDTH.W)
}
