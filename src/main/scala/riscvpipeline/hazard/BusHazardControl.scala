package riscvpipeline.hazard

import chisel3._

final class BusHazardControlIO extends Bundle {
  val IFUStallF = Input(Bool())
  val LSUStallM = Input(Bool())
  val CommittedF = Input(Bool())
  val CommittedM = Input(Bool())
  val TrapM = Input(Bool())
  val RedirectIntentM = Input(Bool())
  val WFIStallM = Input(Bool())
  val StallM = Output(Bool())
  val FlushBusF = Output(Bool())
  val FlushBusM = Output(Bool())
}

/** 教材 §9.5.5、CVW hazard.sv 的总线互锁，纯组合，无时钟或复位端口。
  * 取消意图先于全局 stall 计算；实际提交由上层在 stall 释放后执行。
  * 已接受的事务不得被取消；WFI 停顿由独立的等待单元解除。
  */
final class BusHazardControl extends RawModule {
  val io = IO(new BusHazardControlIO)
  val CancelF = io.RedirectIntentM && !io.CommittedF
  val CancelM = io.TrapM && !io.CommittedM
  io.FlushBusF := CancelF
  io.FlushBusM := CancelM
  io.StallM := io.WFIStallM || (io.IFUStallF && !CancelF) ||
    (io.LSUStallM && !CancelM)
}
