package riscvpipeline.uncore

import chisel3._
import riscvpipeline.ebu.{AHBSlaveIO, APBMasterIO}

final class AHBAPBBridgeIO extends Bundle {
  val ahb = new AHBSlaveIO
  val apb = new APBMasterIO
}

// Adapted from CORE-V-Wally src/uncore/ahbapbbridge.sv.
// Copyright (C) 2021-23 Harvey Mudd College & Oklahoma State University
// SPDX-License-Identifier: Apache-2.0 WITH SHL-2.1
/** 单次 AHB 到 APB 桥：接受地址后完整 Setup 一拍，再进入 Access。
  * AHB 写数据在 Setup（AHB Data）末沿捕获，不能在地址接受沿过早捕获。
  * Access 等待期间所有 APB 请求字段保持；读完成结果在 Idle 期间保留。
  */
final class AHBAPBBridge extends RawModule {
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new AHBAPBBridgeIO)
  private val idle = 0
  private val setup = 1
  private val access = 2
  val State = withClockAndReset(clk, reset) { RegInit(idle.U(2.W)) }
  val Address = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val Write = withClockAndReset(clk, reset) { RegInit(false.B) }
  val WriteData = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val Strobe = withClockAndReset(clk, reset) { RegInit(0.U(4.W)) }
  val HeldReadData = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val Accept = io.ahb.HSEL && io.ahb.HTRANS(1) && io.ahb.HREADY
  val AccessComplete = State === access.U && io.apb.PREADY

  io.apb.PADDR := Address
  io.apb.PWRITE := Write
  io.apb.PSEL := State =/= idle.U
  io.apb.PENABLE := State === access.U
  io.apb.PWDATA := Mux(State === setup.U, io.ahb.HWDATA, WriteData)
  io.apb.PSTRB := Mux(State === setup.U, io.ahb.HWSTRB, Strobe)
  io.ahb.HREADYOUT := State === idle.U || AccessComplete
  io.ahb.HRESP := false.B
  io.ahb.HRDATA := Mux(State === access.U, io.apb.PRDATA, HeldReadData)

  when(State === setup.U) {
    WriteData := io.ahb.HWDATA
    Strobe := io.ahb.HWSTRB
    State := access.U
  }
  when(AccessComplete) {
    HeldReadData := io.apb.PRDATA
    State := idle.U
  }
  // 可在完成旧 Access 的同沿接受下一地址，并重新经历 Setup。
  when(Accept) {
    Address := io.ahb.HADDR(31, 0)
    Write := io.ahb.HWRITE
    State := setup.U
  }
}
