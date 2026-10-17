package riscvpipeline.uncore

import chisel3._

final class AdrDecIO extends Bundle {
  val PhysicalAddress = Input(UInt(34.W))
  val Base = Input(UInt(34.W))
  val Range = Input(UInt(34.W))
  val Supported = Input(Bool())
  val AccessValid = Input(Bool())
  val Size = Input(UInt(2.W))
  val SizeMask = Input(UInt(4.W))
  val Sel = Output(Bool())
}

// Adapted from CORE-V-Wally src/mmu/adrdec.sv.
// Copyright (C) 2021-23 Harvey Mudd College & Oklahoma State University
// SPDX-License-Identifier: Apache-2.0 WITH SHL-2.1
/** 纯组合掩码译码；Range 为二次幂容量减一，Base 按容量对齐。 */
final class AdrDec extends RawModule {
  val io = IO(new AdrDecIO)
  val Match = ((io.PhysicalAddress ^ io.Base) & ~io.Range) === 0.U
  io.Sel := Match && io.Supported && io.AccessValid && io.SizeMask(io.Size)
}
