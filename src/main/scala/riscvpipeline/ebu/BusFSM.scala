package riscvpipeline.ebu

import chisel3._
import chisel3.util.{is, switch}
import riscvpipeline.control.ControlEncoding._

/** BusRW：00 无请求、01 写、10 读；11 及只读实例的写请求被抑制。 */
final class BusFSMIO extends Bundle {
  val Stall = Input(Bool())
  val Flush = Input(Bool())
  val BusRW = Input(UInt(MEM_WIDTH.W))
  val HREADY = Input(Bool())
  val CaptureEn = Output(Bool())
  val BusStall = Output(Bool())
  val BusCommitted = Output(Bool())
  val HTRANS = Output(UInt(2.W))
  val HWRITE = Output(Bool())
}

object BusFSM {
  private val STATE_WIDTH = 2
  private val ADR_PHASE = 0
  private val DATA_PHASE = 1
  private val MEM3 = 2
  private val AHB_IDLE = 0
  private val AHB_NONSEQ = 2
}

// Adapted from CORE-V-Wally src/ebu/busfsm.sv, revision 2f82f001.
// Copyright (C) 2021-23 Harvey Mudd College & Oklahoma State University
// SPDX-License-Identifier: Apache-2.0 WITH SHL-2.1
/** 教材 §9.5.1 图 9.23 的非突发 AHB 控制，显式同域时钟、高有效同步复位。
  * reset 只复位状态寄存器，组合请求和输出不额外与 !reset 门控。
  * Flush 只取消尚未接受的 Adr；已接受的事务必须完成，再等待上层消费 Mem3。
  * Stall 仅保持 Mem3，不得阻止地址接受或 Data 完成，也不能反向门控 BusRW。
  * readOnly 的 Mem3 不置 committed，允许完成取指后处理较老 M 槽的异常。
  */
final class BusFSM(val readOnly: Boolean = false) extends RawModule {
  import BusFSM._

  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new BusFSMIO)

  val State = withClockAndReset(clk, reset) { RegInit(ADR_PHASE.U(STATE_WIDTH.W)) }
  val AcceptedWrite = withClockAndReset(clk, reset) { RegInit(false.B) }
  val ReadRequest = io.BusRW === MEM_READ.U(MEM_WIDTH.W)
  val WriteRequest = (!readOnly).B && io.BusRW === MEM_WRITE.U(MEM_WIDTH.W)
  val AddressRequest = (ReadRequest || WriteRequest) && !io.Flush
  val AddressAccept = State === ADR_PHASE.U(STATE_WIDTH.W) && AddressRequest && io.HREADY

  val StateNext = WireDefault(ADR_PHASE.U(STATE_WIDTH.W))
  switch(State) {
    is(ADR_PHASE.U(STATE_WIDTH.W)) {
      StateNext := Mux(AddressAccept, DATA_PHASE.U(STATE_WIDTH.W), ADR_PHASE.U(STATE_WIDTH.W))
    }
    is(DATA_PHASE.U(STATE_WIDTH.W)) {
      StateNext := Mux(io.HREADY, MEM3.U(STATE_WIDTH.W), DATA_PHASE.U(STATE_WIDTH.W))
    }
    is(MEM3.U(STATE_WIDTH.W)) {
      StateNext := Mux(io.Stall, MEM3.U(STATE_WIDTH.W), ADR_PHASE.U(STATE_WIDTH.W))
    }
  }
  State := StateNext
  when(AddressAccept) { AcceptedWrite := WriteRequest }

  // 在 Data 完成沿独立采样读数据；全局 Stall 或 Flush 不得丢弃已接受的响应。
  io.CaptureEn := State === DATA_PHASE.U(STATE_WIDTH.W) &&
    io.HREADY && !AcceptedWrite
  io.BusStall := (State === ADR_PHASE.U(STATE_WIDTH.W) && AddressRequest) ||
    State === DATA_PHASE.U(STATE_WIDTH.W)
  io.BusCommitted := State === DATA_PHASE.U(STATE_WIDTH.W) ||
    ((!readOnly).B && State === MEM3.U(STATE_WIDTH.W))
  io.HTRANS := Mux(AddressAccept, AHB_NONSEQ.U(2.W), AHB_IDLE.U(2.W))
  io.HWRITE := (State === ADR_PHASE.U(STATE_WIDTH.W) && AddressRequest && WriteRequest) ||
    (State === DATA_PHASE.U(STATE_WIDTH.W) && AcceptedWrite)
}
