package riscvpipeline.ebu

import chisel3._
import riscvpipeline.isa.Architecture.XLEN

final class AHBInterfaceIO extends Bundle {
  val Stall = Input(Bool())
  val Flush = Input(Bool())
  val BusRW = Input(UInt(2.W))
  val HREADY = Input(Bool())
  val HRDATA = Input(UInt(XLEN.W))
  val ByteMask = Input(UInt((XLEN / 8).W))
  val WriteData = Input(UInt(XLEN.W))
  val HTRANS = Output(UInt(2.W))
  val HWRITE = Output(Bool())
  val HWDATA = Output(UInt(XLEN.W))
  val HWSTRB = Output(UInt((XLEN / 8).W))
  val BusStall = Output(Bool())
  val BusCommitted = Output(Bool())
  val FetchBuffer = Output(UInt(XLEN.W))
}

// Adapted from CORE-V-Wally src/ebu/ahbinterface.sv.
// Copyright (C) 2021-23 Harvey Mudd College & Oklahoma State University
// SPDX-License-Identifier: Apache-2.0 WITH SHL-2.1
/** 非突发接口：地址接受沿保存写载荷，Data 完成沿保存读响应。
  * 显式 clk/reset；缓冲寄存器同步复位。Stall 只控制 BusFSM 的 Mem3 消费。
  */
final class AHBInterface(val readOnly: Boolean = false) extends RawModule {
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new AHBInterfaceIO)

  val busFSM = Module(new BusFSM(readOnly))
  busFSM.clk := clk
  busFSM.reset := reset
  busFSM.io.Stall := io.Stall
  busFSM.io.Flush := io.Flush
  busFSM.io.BusRW := io.BusRW
  busFSM.io.HREADY := io.HREADY
  io.HTRANS := busFSM.io.HTRANS
  io.HWRITE := busFSM.io.HWRITE
  io.BusStall := busFSM.io.BusStall
  io.BusCommitted := busFSM.io.BusCommitted

  val FetchBuffer = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
  when(busFSM.io.CaptureEn) { FetchBuffer := io.HRDATA }
  io.FetchBuffer := FetchBuffer

  if (readOnly) {
    io.HWDATA := 0.U(XLEN.W)
    io.HWSTRB := 0.U((XLEN / 8).W)
  } else {
    val WriteBuffer = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
    val MaskBuffer = withClockAndReset(clk, reset) { RegInit(0.U((XLEN / 8).W)) }
    val WriteAccept = io.HTRANS === 2.U(2.W) && io.HREADY && io.HWRITE
    when(WriteAccept) {
      WriteBuffer := io.WriteData
      MaskBuffer := io.ByteMask
    }
    io.HWDATA := WriteBuffer
    io.HWSTRB := MaskBuffer
  }
}
