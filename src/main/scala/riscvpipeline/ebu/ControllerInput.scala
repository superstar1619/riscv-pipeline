package riscvpipeline.ebu

import chisel3._
import chisel3.util.Cat

final class ControllerInputIO extends Bundle {
  val Save = Input(Bool())
  val Restore = Input(Bool())
  val Disable = Input(Bool())
  val Request = Output(Bool())
  val HTRANSIn = Input(UInt(2.W))
  val HWRITEIn = Input(Bool())
  val HSIZEIn = Input(UInt(3.W))
  val HBURSTIn = Input(UInt(3.W))
  val HADDRIn = Input(UInt(34.W))
  val HREADYOut = Output(Bool())
  val HTRANSOut = Output(UInt(2.W))
  val HWRITEOut = Output(Bool())
  val HSIZEOut = Output(UInt(3.W))
  val HBURSTOut = Output(UInt(3.W))
  val HADDROut = Output(UInt(34.W))
  val HREADYIn = Input(Bool())
}

// Adapted from CORE-V-Wally src/ebu/controllerinput.sv.
// Copyright (C) 2021-23 Harvey Mudd College & Oklahoma State University
// SPDX-License-Identifier: Apache-2.0 WITH SHL-2.1
/** 地址相位保存/重放；Save 更新同步寄存器，Restore 仅选择输出。
  * saveEnabled=false 用于始终优先的 LSU，不生成保存寄存器。
  */
final class ControllerInput(val saveEnabled: Boolean = true) extends RawModule {
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new ControllerInputIO)

  val Live = Cat(io.HWRITEIn, io.HSIZEIn, io.HBURSTIn, io.HTRANSIn, io.HADDRIn)
  val Selected = if (saveEnabled) {
    val Saved = withClockAndReset(clk, reset) { RegInit(0.U(43.W)) }
    when(io.Save) { Saved := Live }
    Mux(io.Restore, Saved, Live)
  } else Live

  io.HADDROut := Selected(33, 0)
  io.HTRANSOut := Selected(35, 34)
  io.HBURSTOut := Selected(38, 36)
  io.HSIZEOut := Selected(41, 39)
  io.HWRITEOut := Selected(42)
  io.Request := io.HTRANSOut =/= 0.U(2.W)
  io.HREADYOut := io.HREADYIn && !io.Disable
}
