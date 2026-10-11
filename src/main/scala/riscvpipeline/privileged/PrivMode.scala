package riscvpipeline.privileged

import chisel3._
import chisel3.util.Cat
import PrivilegedConstants.Privilege._

/** 已仲裁陷阱和返回请求；状态栈与非法指令异常由其他模块处理。 */
final class PrivModeIO extends Bundle {
  val ValidM = Input(Bool())
  val StallW = Input(Bool())
  val FlushM = Input(Bool())
  val TrapM = Input(Bool())
  val mretM = Input(Bool())
  val sretM = Input(Bool())
  val DelegateM = Input(Bool())
  val STATUS_MPP = Input(UInt(MODE_WIDTH.W))
  val STATUS_SPP = Input(Bool())
  val STATUS_TSR = Input(Bool())

  val NextPrivilegeModeM = Output(UInt(MODE_WIDTH.W))
  val PrivilegeModeW = Output(UInt(MODE_WIDTH.W))
}

/** 教材 §8.4.5：特权级状态，U/M 始终支持，S 可选。
  * 显式 clk、高有效同步 reset，复位到 M；组合候选不受提交门控影响。
  * 返回取沿前 MPP/SPP，状态栈、异常仲裁和 PC 更新由其他模块负责。
  */
final class PrivMode(supervisorSupported: Boolean = true) extends RawModule {
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new PrivModeIO)

  val PrivilegeMode = withClockAndReset(clk, reset) { RegInit(MODE_M.U(MODE_WIDTH.W)) }
  val MachineModeM = PrivilegeMode === MODE_M.U(MODE_WIDTH.W)
  val SupervisorModeM = PrivilegeMode === MODE_S.U(MODE_WIDTH.W)
  val MPPAllowedM = io.STATUS_MPP === MODE_U.U(MODE_WIDTH.W) ||
    io.STATUS_MPP === MODE_M.U(MODE_WIDTH.W) ||
    (supervisorSupported.B && io.STATUS_MPP === MODE_S.U(MODE_WIDTH.W))
  val SretAllowedM = supervisorSupported.B &&
    (MachineModeM || (SupervisorModeM && !io.STATUS_TSR))
  val NextPrivilegeModeM = WireDefault(PrivilegeMode)

  // 原始高优先级事件即使非法也占据分支；不落入低优先级返回。
  when(io.TrapM) {
    // 陷阱不能降低特权级，即使上游误置 M 模式的 DelegateM。
    NextPrivilegeModeM := Mux(supervisorSupported.B && io.DelegateM && !MachineModeM,
      MODE_S.U(MODE_WIDTH.W), MODE_M.U(MODE_WIDTH.W))
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
