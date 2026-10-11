package riscvpipeline.privileged

import chisel3._
import chisel3.util.Cat
import riscvpipeline.isa.Architecture._
import PrivilegedConstants._

/** CSR写请求已经过调用方授权；中断输入须已同步到clk域，视图使用低12位。 */
final class CSRInterruptIO extends Bundle {
  val ValidM = Input(Bool())
  val StallW = Input(Bool())
  val FlushM = Input(Bool())
  val WriteMIEM = Input(Bool())
  val WriteMIPM = Input(Bool())
  val WriteSIEM = Input(Bool())
  val WriteSIPM = Input(Bool())
  val CSRWriteValM = Input(UInt(XLEN.W))
  val MIDELEG_REGW = Input(UInt(Interrupt.WIDTH.W))
  val MExtInt = Input(Bool())
  val SExtInt = Input(Bool())
  val MTimerInt = Input(Bool())
  val STimerInt = Input(Bool())
  val MSwInt = Input(Bool())
  val ENVCFG_STCE = Input(Bool())

  val MIP_REGW = Output(UInt(Interrupt.WIDTH.W))
  val MIE_REGW = Output(UInt(Interrupt.WIDTH.W))
  val SIP_REGW = Output(UInt(Interrupt.WIDTH.W))
  val SIE_REGW = Output(UInt(Interrupt.WIDTH.W))
  val MIP_REGW_writeable = Output(UInt(Interrupt.WIDTH.W))
  val SIP_REGW_writeable = Output(UInt(Interrupt.WIDTH.W))
}

/** RV32共享中断CSR，教材§8.2.5.3–4、§8.4.3；独立于CPU。
  * 显式clk、高有效同步reset；软件状态边沿更新，硬件pending及委托视图纯组合。
  * Sstc只控制输入选择与写掩码，不实现计时、stimecmp或menvcfg。
  */
final class CSRInterrupt(
  supervisorSupported: Boolean = true,
  sstcSupported: Boolean = false
) extends RawModule {
  require(!sstcSupported || supervisorSupported, "Sstc requires supervisor support")
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new CSRInterruptIO)

  private val S_MASK = if (supervisorSupported) Interrupt.SUPERVISOR_MASK else 0
  private val MIE_MASK =
    if (supervisorSupported) Interrupt.SUPPORTED_MASK else Interrupt.MACHINE_MASK
  val Enable = withClockAndReset(clk, reset) { RegInit(0.U(Interrupt.WIDTH.W)) }
  val PendingSW = withClockAndReset(clk, reset) { RegInit(0.U(Interrupt.WIDTH.W)) }
  val UpdateM = io.ValidM && !io.StallW && !io.FlushM
  val WriteWordM = io.CSRWriteValM(Interrupt.WIDTH - 1, 0)
  val DelegatedMask = io.MIDELEG_REGW & S_MASK.U(Interrupt.WIDTH.W)
  val SIPWriteMask = DelegatedMask & Interrupt.SSIP_MASK.U(Interrupt.WIDTH.W)
  val MIPWriteMask = if (sstcSupported) {
    Mux(io.ENVCFG_STCE, Interrupt.STCE_SOFTWARE_MASK.U(Interrupt.WIDTH.W),
      Interrupt.SUPERVISOR_MASK.U(Interrupt.WIDTH.W))
  } else {
    S_MASK.U(Interrupt.WIDTH.W)
  }

  // 两组状态分别更新；机器写优先。S视图只能合并当前委托的可写位。
  withClockAndReset(clk, reset) {
    when(UpdateM) {
      when(io.WriteMIEM) {
        Enable := WriteWordM & MIE_MASK.U(Interrupt.WIDTH.W)
      }.elsewhen(io.WriteSIEM) {
        Enable := (WriteWordM & DelegatedMask) | (Enable & ~DelegatedMask)
      }
      when(io.WriteMIPM) {
        // 与CVW一致：整字MIP写替换软件状态，STCE=1时清除潜藏的软件STIP。
        PendingSW := WriteWordM & MIPWriteMask
      }.elsewhen(io.WriteSIPM) {
        PendingSW := (WriteWordM & SIPWriteMask) | (PendingSW & ~SIPWriteMask)
      }
    }
  }

  val SEIP = if (supervisorSupported) io.SExtInt || PendingSW(Interrupt.SEIP_BIT) else false.B
  val SSIP = if (supervisorSupported) PendingSW(Interrupt.SSIP_BIT) else false.B
  val STIP = if (sstcSupported) {
    Mux(io.ENVCFG_STCE, io.STimerInt, PendingSW(Interrupt.STIP_BIT))
  } else if (supervisorSupported) {
    PendingSW(Interrupt.STIP_BIT)
  } else {
    false.B
  }
  val Pending = Cat(io.MExtInt, false.B, SEIP, false.B, io.MTimerInt, false.B,
    STIP, false.B, io.MSwInt, false.B, SSIP, false.B)

  // 软件读值可含硬件电平；修改基值只取内部状态，避免SExtInt被写回后粘住。
  io.MIP_REGW := Pending
  io.MIE_REGW := Enable
  io.SIP_REGW := Pending & DelegatedMask
  io.SIE_REGW := Enable & DelegatedMask
  io.MIP_REGW_writeable := PendingSW
  io.SIP_REGW_writeable := PendingSW & DelegatedMask
}
