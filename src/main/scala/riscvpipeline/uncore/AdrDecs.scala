package riscvpipeline.uncore

import chisel3._
import chisel3.util.Cat
import riscvpipeline.config.BusConfig

final class AdrDecsIO extends Bundle {
  val HADDR = Input(UInt(34.W))
  val HSIZE = Input(UInt(3.W))
  val HSEL = Output(UInt(4.W))
}

// Adapted from CORE-V-Wally src/mmu/adrdecs.sv.
// Copyright (C) 2021-23 Harvey Mudd College & Oklahoma State University
// SPDX-License-Identifier: Apache-2.0 WITH SHL-2.1
/** 组合窗口译码，None/ROM/RAM/APB 分别为 bit0/1/2/3。 */
final class AdrDecs(config: BusConfig = BusConfig()) extends RawModule {
  val io = IO(new AdrDecsIO)
  val selected = Seq((config.bootRomBase, config.bootRomBytes),
    (config.ramBase, config.ramBytes), (config.apbBase, config.apbBytes)).map {
    case (base, bytes) =>
      val decoder = Module(new AdrDec)
      decoder.io.PhysicalAddress := io.HADDR
      decoder.io.Base := base.U(34.W)
      decoder.io.Range := (bytes - 1).U(34.W)
      decoder.io.Supported := true.B
      decoder.io.AccessValid := !io.HSIZE(2)
      decoder.io.Size := io.HSIZE(1, 0)
      decoder.io.SizeMask := 7.U(4.W)
      decoder.io.Sel
  }
  io.HSEL := Cat(selected.reverse :+ !selected.reduce(_ || _))
}
