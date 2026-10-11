package riscvpipeline.privileged

import chisel3._
import chisel3.util.log2Ceil

/** M 级等待请求与局部使能后的 pending；计数单位为 clk 的上升沿。 */
final class WFIIO(timeoutCycles: Int) extends Bundle {
  val ValidM = Input(Bool())
  val wfiM = Input(Bool())
  val FlushM = Input(Bool())
  val TrapM = Input(Bool())
  val IntPendingM = Input(Bool())

  val WFIStallM = Output(Bool())
  val WFITimeoutM = Output(Bool())
  val WaitCycles = Output(UInt(log2Ceil(timeoutCycles + 1).W))
}

object WFI {
  val DEFAULT_TIMEOUT_CYCLES: Int = 65536
  val MAX_TIMEOUT_CYCLES: Int = 1 << 24
}

/** 教材 §8.2.2.4、§8.4.2：有界 WFI 等待，显式 clk、高有效同步 reset。
  * 等待计数不受自身 stall 冻结，阈值只报告到期，TW/模式资格留给 PrivDecode。
  * Trap 不组合撤销 Timeout，允许到期异常经 Trap 仲裁后同步取消当前等待。
  */
final class WFI(timeoutCycles: Int = WFI.DEFAULT_TIMEOUT_CYCLES) extends RawModule {
  require(timeoutCycles >= 1 && timeoutCycles <= WFI.MAX_TIMEOUT_CYCLES,
    "WFI timeoutCycles must be in 1..2^24")

  private val countWidth = log2Ceil(timeoutCycles + 1)
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new WFIIO(timeoutCycles))

  val WaitCount = withClockAndReset(clk, reset) { RegInit(0.U(countWidth.W)) }
  val TimeoutEligibleM = io.ValidM && io.wfiM && !io.FlushM
  val ActiveM = TimeoutEligibleM && !io.TrapM
  val ExpiredM = WaitCount >= timeoutCycles.U(countWidth.W)

  withClockAndReset(clk, reset) {
    when(!ActiveM || io.IntPendingM) {
      WaitCount := 0.U(countWidth.W)
    }.elsewhen(!ExpiredM) {
      // countWidth 能表示阈值；递增仅发生在阈值之前，不会发生回绕。
      WaitCount := WaitCount + 1.U(countWidth.W)
    }
  }

  io.WFIStallM := ActiveM && !io.IntPendingM && !ExpiredM
  io.WFITimeoutM := TimeoutEligibleM && ExpiredM
  io.WaitCycles := WaitCount
}
