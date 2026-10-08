package riscvpipeline.privileged

import chisel3._

/** 第八章公共配置；固定RV32、U/M与Bare地址空间，S和相关扩展可选。 */
final case class PrivilegedConfig(
  supervisorSupported: Boolean = true,
  bigEndianSupported: Boolean = true,
  sstcSupported: Boolean = false,
  vectoredInterruptsSupported: Boolean = true,
  hpmCounters: Int = 0,
  hartId: BigInt = 0,
  wfiTimeoutCycles: Int = 65536
) {
  require(!sstcSupported || supervisorSupported, "Sstc requires supervisor support")
  require(hpmCounters >= 0 && hpmCounters <= 29, "HPM counter count must be 0..29")
  require(hartId >= 0 && hartId < (BigInt(1) << 32), "hartId must fit unsigned RV32")
  require(wfiTimeoutCycles >= 1 && wfiTimeoutCycles <= (1 << 24),
    "WFI timeout must be 1..2^24 cycles")
}

/** 所有中断电平须已同步；TIME_REGW须是clk同域一致的64位时间值。 */
final class InterruptInputs extends Bundle {
  val MExtInt = Bool()
  val SExtInt = Bool()
  val MTimerInt = Bool()
  val MSwInt = Bool()
  val TIME_REGW = UInt(64.W)
}
