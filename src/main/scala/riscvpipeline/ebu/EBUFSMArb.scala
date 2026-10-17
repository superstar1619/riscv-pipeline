package riscvpipeline.ebu

import chisel3._

final class EBUFSMArbIO extends Bundle {
  val HBURST = Input(UInt(3.W))
  val HREADY = Input(Bool())
  val LSUReq = Input(Bool())
  val IFUReq = Input(Bool())
  val IFUSave = Output(Bool())
  val IFURestore = Output(Bool())
  val IFUDisable = Output(Bool())
  val IFUSelect = Output(Bool())
  val LSUDisable = Output(Bool())
  val LSUSelect = Output(Bool())
}

// Adapted from CORE-V-Wally src/ebu/ebufsmarb.sv.
// Copyright (C) 2021-23 Harvey Mudd College & Oklahoma State University
// SPDX-License-Identifier: Apache-2.0 WITH SHL-2.1
/** 单次 NONSEQ 的双状态 LSU 优先仲裁；无 burst 计数。
  * HBURST 保留 CVW 端口，但上层仅发送 SINGLE。
  * 同步复位只作用于状态和请求延迟寄存器，不门控组合输出。
  */
final class EBUFSMArb extends RawModule {
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new EBUFSMArbIO)

  val Arbitrate = withClockAndReset(clk, reset) { RegInit(false.B) }
  val IFUReqDelay = withClockAndReset(clk, reset) { RegInit(false.B) }
  val Both = io.LSUReq && io.IFUReq
  val ArbitrateNext = Mux(Arbitrate, !(io.HREADY && !Both), Both)
  Arbitrate := ArbitrateNext
  IFUReqDelay := io.IFUReq

  io.IFUSave := !Arbitrate && Both
  io.IFURestore := Arbitrate
  io.IFUDisable := Arbitrate
  io.IFUSelect := !ArbitrateNext && io.IFUReq
  io.LSUDisable := !Arbitrate && IFUReqDelay
  io.LSUSelect := ArbitrateNext || io.LSUReq
}
