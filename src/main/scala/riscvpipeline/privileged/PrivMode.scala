package riscvpipeline.privileged

import chisel3._
import chisel3.util.Cat

/** 已仲裁陷阱和返回请求；状态栈与非法指令异常由其他模块处理。 */
final class PrivModeIO extends Bundle {
  val ValidM = Input(Bool())
  val StallW = Input(Bool())
  val FlushM = Input(Bool())
  val TrapM = Input(Bool())
  val mretM = Input(Bool())
  val sretM = Input(Bool())
  val DelegateM = Input(Bool())
  val STATUS_MPP = Input(UInt(2.W))
  val STATUS_SPP = Input(Bool())
  val STATUS_TSR = Input(Bool())

  val NextPrivilegeModeM = Output(UInt(2.W))
  val PrivilegeModeW = Output(UInt(2.W))
}

/** 教材 §8.4.5：特权级状态，U/M 始终支持，S 可选。
  * 显式 clk、高有效同步 reset，复位到 M；组合候选不受提交门控影响。
  * 返回取沿前 MPP/SPP，状态栈、异常仲裁和 PC 更新由其他模块负责。
  */
final class PrivMode(supervisorSupported: Boolean = true) extends RawModule {
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new PrivModeIO)

  val PrivilegeMode = withClockAndReset(clk, reset) { RegInit(3.U(2.W)) }
  val MachineModeM = PrivilegeMode === 3.U(2.W)
  val SupervisorModeM = PrivilegeMode === 1.U(2.W)
  val MPPAllowedM = io.STATUS_MPP === 0.U(2.W) || io.STATUS_MPP === 3.U(2.W) ||
    (supervisorSupported.B && io.STATUS_MPP === 1.U(2.W))
  val SretAllowedM = supervisorSupported.B &&
    (MachineModeM || (SupervisorModeM && !io.STATUS_TSR))
  val NextPrivilegeModeM = WireDefault(PrivilegeMode)

  // 原始高优先级事件即使非法也占据分支；不落入低优先级返回。
  when(io.TrapM) {
    // 陷阱不能降低特权级，即使上游误置 M 模式的 DelegateM。
    NextPrivilegeModeM := Mux(supervisorSupported.B && io.DelegateM && !MachineModeM,
      1.U(2.W), 3.U(2.W))
  }.elsewhen(io.mretM) {
    when(MachineModeM && MPPAllowedM) { NextPrivilegeModeM := io.STATUS_MPP }
  }.elsewhen(io.sretM) {
    when(SretAllowedM) { NextPrivilegeModeM := Cat(0.U(1.W), io.STATUS_SPP) }
  }

  withClockAndReset(clk, reset) {
    when(io.ValidM && !io.StallW && !io.FlushM) {
      PrivilegeMode := NextPrivilegeModeM
    }
  }
  io.NextPrivilegeModeM := NextPrivilegeModeM
  io.PrivilegeModeW := PrivilegeMode
}
