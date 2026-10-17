package riscvpipeline.uncore

import chisel3._
import chisel3.util.log2Ceil
import riscvpipeline.ebu.AHBSlaveIO

// Adapted from CORE-V-Wally src/uncore/ram_ahb.sv.
// Copyright (C) 2021-23 Harvey Mudd College & Oklahoma State University
// SPDX-License-Identifier: Apache-2.0 WITH SHL-2.1
/** 原生同步读 RAM；一次调度一个存储器操作，不预初始化。
  * 地址相位仅在 HSEL && HTRANS[1] && HREADY 接受。写在数据完成沿执行一次，
  * 紧随写完成接受的读延后一拍，避免单端口冲突；额外等待不改变读响应。
  */
final class RAMAHB(
  base: BigInt = BigInt("80000000", 16),
  sizeBytes: Int = 65536,
  extraLatency: Int = 0
) extends RawModule {
  require(sizeBytes >= 4 && (sizeBytes & (sizeBytes - 1)) == 0)
  require(base >= 0 && base % sizeBytes == 0 && base + sizeBytes <= (BigInt(1) << 34))
  require(extraLatency >= 0 && extraLatency <= 255)
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new AHBSlaveIO)
  private val indexWidth = math.max(1, log2Ceil(sizeBytes / 4))
  def index(address: UInt): UInt = ((address - base.U(34.W)) >> 2)(indexWidth - 1, 0)

  val Pending = withClockAndReset(clk, reset) { RegInit(false.B) }
  val Write = withClockAndReset(clk, reset) { RegInit(false.B) }
  val Address = withClockAndReset(clk, reset) { RegInit(0.U(indexWidth.W)) }
  val Wait = withClockAndReset(clk, reset) { RegInit(0.U(8.W)) }
  val DeferredRead = withClockAndReset(clk, reset) { RegInit(false.B) }
  val Accept = io.HSEL && io.HTRANS(1) && io.HREADY
  io.HREADYOUT := !Pending || (Wait === 0.U && !DeferredRead)
  io.HRESP := false.B
  val Complete = Pending && io.HREADY && io.HREADYOUT
  val WriteEnable = Complete && Write
  val ReadRequest = Accept && !io.HWRITE
  val ReadEnable = DeferredRead || (ReadRequest && !WriteEnable)
  val PortAddress = Mux(WriteEnable || DeferredRead, Address, index(io.HADDR))
  val RAM = withClock(clk) { SyncReadMem(sizeBytes / 4, Vec(4, UInt(8.W))) }
  val WriteBytes = Wire(Vec(4, UInt(8.W)))
  for (lane <- 0 until 4) { WriteBytes(lane) := io.HWDATA(8 * lane + 7, 8 * lane) }
  val ReadBytes = WireDefault(VecInit(Seq.fill(4)(0.U(8.W))))
  // 一个原生 masked readWrite 端口；复位优先抑制存储器时序副作用。
  when(reset) {
  }.otherwise {
    ReadBytes := RAM.readWrite(PortAddress, WriteBytes, io.HWSTRB.asBools,
      ReadEnable || WriteEnable, WriteEnable, clk)
  }
  val ReadIssued = withClockAndReset(clk, reset) { RegNext(ReadEnable, false.B) }
  val HeldData = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  when(ReadIssued) { HeldData := ReadBytes.asUInt }
  io.HRDATA := Mux(ReadIssued, ReadBytes.asUInt, HeldData)

  DeferredRead := ReadRequest && WriteEnable
  when(Pending && !DeferredRead && Wait =/= 0.U) { Wait := Wait - 1.U }
  when(Complete) { Pending := false.B }
  when(Accept) {
    Pending := true.B
    Write := io.HWRITE
    Address := index(io.HADDR)
    Wait := extraLatency.U
  }
}
