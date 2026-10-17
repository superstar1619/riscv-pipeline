package riscvpipeline.uncore

import chisel3._
import chisel3.util.MuxCase
import riscvpipeline.config.BusConfig
import riscvpipeline.ebu.AHBMasterIO

final class UncoreIO extends Bundle {
  val ahb = Flipped(new AHBMasterIO)
}

// Adapted from CORE-V-Wally src/uncore/uncore.sv.
// Copyright (C) 2021-23 Harvey Mudd College & Oklahoma State University
// SPDX-License-Identifier: Apache-2.0 WITH SHL-2.1
/** 单主 AHB 的 BootROM、RAM、最小 APB 子系统，显式同域 clk/reset。
  * 数据相位使用仅在全局 HREADY 上升沿更新的地址相位 HSEL，等待期间保持归属。
  * Bool 同步复位选择 None：HREADY=1、HRDATA/HRESP=0，避免复位后握手死锁。
  */
final class Uncore(config: BusConfig = BusConfig()) extends RawModule {
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new UncoreIO)
  val decoder = Module(new AdrDecs(config))
  decoder.io.HADDR := io.ahb.HADDR
  decoder.io.HSIZE := io.ahb.HSIZE
  val rom = Module(new ROMAHB(config.bootRomBase, config.bootRomBytes,
    config.bootRomInitFile, config.romLatency))
  val ram = Module(new RAMAHB(config.ramBase, config.ramBytes, config.ramLatency))
  val bridge = Module(new AHBAPBBridge)
  val registerBank = Module(new APBRegisterBank(config.apbLatency))
  rom.clk := clk
  rom.reset := reset
  ram.clk := clk
  ram.reset := reset
  bridge.clk := clk
  bridge.reset := reset
  registerBank.clk := clk
  registerBank.reset := reset

  for ((slave, region) <- Seq((rom.io, 1), (ram.io, 2), (bridge.io.ahb, 3))) {
    slave.HSEL := decoder.io.HSEL(region)
    slave.HADDR := io.ahb.HADDR
    slave.HWDATA := io.ahb.HWDATA
    slave.HWSTRB := io.ahb.HWSTRB
    slave.HWRITE := io.ahb.HWRITE
    slave.HSIZE := io.ahb.HSIZE
    slave.HTRANS := io.ahb.HTRANS
    slave.HREADY := io.ahb.HREADY
  }
  registerBank.io.apb <> bridge.io.apb
  // APB 叶设备只看完整窗口相对偏移，避免大窗口内每4KiB重复译码。
  private val apbBaseLow = config.apbBase & ((BigInt(1) << 32) - 1)
  registerBank.io.apb.PADDR := bridge.io.apb.PADDR - apbBaseLow.U(32.W)

  val Selected = withClockAndReset(clk, reset) { RegInit(1.U(4.W)) }
  when(io.ahb.HREADY) { Selected := decoder.io.HSEL }
  io.ahb.HRDATA := MuxCase(0.U(32.W), Seq(
    Selected(1) -> rom.io.HRDATA,
    Selected(2) -> ram.io.HRDATA,
    Selected(3) -> bridge.io.ahb.HRDATA))
  io.ahb.HREADY := MuxCase(true.B, Seq(
    Selected(1) -> rom.io.HREADYOUT,
    Selected(2) -> ram.io.HREADYOUT,
    Selected(3) -> bridge.io.ahb.HREADYOUT))
  io.ahb.HRESP := MuxCase(false.B, Seq(
    Selected(1) -> rom.io.HRESP,
    Selected(2) -> ram.io.HRESP,
    Selected(3) -> bridge.io.ahb.HRESP))
}
