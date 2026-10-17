package riscvpipeline.uncore

import chisel3._
import chisel3.util.log2Ceil
import chisel3.util.experimental.loadMemoryFromFileInline
import riscvpipeline.ebu.AHBSlaveIO

// Adapted from CORE-V-Wally src/uncore/rom_ahb.sv.
// Copyright (C) 2021-23 Harvey Mudd College & Oklahoma State University
// SPDX-License-Identifier: Apache-2.0 WITH SHL-2.1
/** 原生同步读 BootROM，镜像为每行一个 32 位十六进制字；不随复位重载。
  * extraLatency 仅增加数据相位等待；最后一次有效读在等待及空闲期间保持。
  * 写以 OKAY 完成并忽略，未初始化内容不作保证。
  */
final class ROMAHB(
  base: BigInt = 0x1000,
  sizeBytes: Int = 4096,
  initFile: Option[String] = None,
  extraLatency: Int = 0
) extends RawModule {
  require(sizeBytes >= 4 && (sizeBytes & (sizeBytes - 1)) == 0)
  require(base >= 0 && base % sizeBytes == 0 && base + sizeBytes <= (BigInt(1) << 34))
  require(extraLatency >= 0 && extraLatency <= 255)
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new AHBSlaveIO)
  private val indexWidth = math.max(1, log2Ceil(sizeBytes / 4))
  val WordIndex = ((io.HADDR - base.U(34.W)) >> 2)(indexWidth - 1, 0)
  val Accept = io.HSEL && io.HTRANS(1) && io.HREADY
  val ReadEnable = Accept && !io.HWRITE
  val ROM = withClock(clk) { SyncReadMem(sizeBytes / 4, UInt(32.W)) }
  initFile.foreach(path => loadMemoryFromFileInline(ROM, path))
  val ReadData = ROM.read(WordIndex, ReadEnable, clk)
  val ReadIssued = withClockAndReset(clk, reset) { RegNext(ReadEnable, false.B) }
  val HeldData = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  when(ReadIssued) { HeldData := ReadData }
  io.HRDATA := Mux(ReadIssued, ReadData, HeldData)
  val Pending = withClockAndReset(clk, reset) { RegInit(false.B) }
  val Wait = withClockAndReset(clk, reset) { RegInit(0.U(8.W)) }
  io.HREADYOUT := !Pending || Wait === 0.U
  io.HRESP := false.B
  when(Pending && Wait =/= 0.U) { Wait := Wait - 1.U }
  when(Pending && io.HREADY && io.HREADYOUT) { Pending := false.B }
  when(Accept) {
    Pending := true.B
    Wait := extraLatency.U
  }
}
