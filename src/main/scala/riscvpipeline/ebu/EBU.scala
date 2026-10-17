package riscvpipeline.ebu

import chisel3._

final class EBUIO extends Bundle {
  val IFUHTRANS = Input(UInt(2.W))
  val IFUHSIZE = Input(UInt(3.W))
  val IFUHBURST = Input(UInt(3.W))
  val IFUHADDR = Input(UInt(34.W))
  val IFUHREADY = Output(Bool())
  val IFUHRDATA = Output(UInt(32.W))
  val LSUHTRANS = Input(UInt(2.W))
  val LSUHWRITE = Input(Bool())
  val LSUHSIZE = Input(UInt(3.W))
  val LSUHBURST = Input(UInt(3.W))
  val LSUHADDR = Input(UInt(34.W))
  val LSUHWDATA = Input(UInt(32.W))
  val LSUHWSTRB = Input(UInt(4.W))
  val LSUHREADY = Output(Bool())
  val LSUHRDATA = Output(UInt(32.W))
  val HCLK = Output(Clock())
  val HRESETn = Output(Bool())
  val HREADY = Input(Bool())
  val HRESP = Input(Bool())
  val HRDATA = Input(UInt(32.W))
  val HADDR = Output(UInt(34.W))
  val HWDATA = Output(UInt(32.W))
  val HWSTRB = Output(UInt(4.W))
  val HWRITE = Output(Bool())
  val HSIZE = Output(UInt(3.W))
  val HBURST = Output(UInt(3.W))
  val HPROT = Output(UInt(4.W))
  val HTRANS = Output(UInt(2.W))
  val HMASTLOCK = Output(Bool())
}

// Adapted from CORE-V-Wally src/ebu/ebu.sv.
// Copyright (C) 2021-23 Harvey Mudd College & Oklahoma State University
// SPDX-License-Identifier: Apache-2.0 WITH SHL-2.1
/** RV32 单次 AHB 合并；LSU 优先，IFU 冲突时虚拟接受并保存地址，随后重放。
  * Pending 记录每方已离开 Adr 的请求；DataOwner 记录实际外部 Data 相位归属。
  * ready 由寄存器归属决定，既不提前完成另一方，也不形成 HTRANS/ready 组合反馈。
  * HRESP 仅预留；本配置仅支持 OKAY。显式同步复位，不增加组合 reset 门控。
  */
final class EBU extends RawModule {
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new EBUIO)

  private val NoOwner = 0
  private val IFUOwner = 1
  private val LSUOwner = 2
  val DataOwner = withClockAndReset(clk, reset) { RegInit(NoOwner.U(2.W)) }
  val IFUPending = withClockAndReset(clk, reset) { RegInit(false.B) }
  val LSUPending = withClockAndReset(clk, reset) { RegInit(false.B) }

  val ifuInput = Module(new ControllerInput(true))
  val lsuInput = Module(new ControllerInput(false))
  val arb = Module(new EBUFSMArb)
  for (m <- Seq(ifuInput, lsuInput)) {
    m.clk := clk
    m.reset := reset
  }
  arb.clk := clk
  arb.reset := reset
  arb.io.HREADY := io.HREADY
  arb.io.HBURST := 0.U(3.W)
  arb.io.IFUReq := ifuInput.io.Request
  arb.io.LSUReq := lsuInput.io.Request

  ifuInput.io.Save := arb.io.IFUSave
  ifuInput.io.Restore := arb.io.IFURestore
  ifuInput.io.Disable := arb.io.IFUDisable
  ifuInput.io.HTRANSIn := io.IFUHTRANS
  ifuInput.io.HWRITEIn := false.B
  ifuInput.io.HSIZEIn := io.IFUHSIZE
  ifuInput.io.HBURSTIn := io.IFUHBURST
  ifuInput.io.HADDRIn := io.IFUHADDR
  ifuInput.io.HREADYIn := io.HREADY &&
    (!IFUPending || DataOwner === IFUOwner.U(2.W))
  io.IFUHREADY := ifuInput.io.HREADYOut

  lsuInput.io.Save := false.B
  lsuInput.io.Restore := false.B
  lsuInput.io.Disable := arb.io.LSUDisable
  lsuInput.io.HTRANSIn := io.LSUHTRANS
  lsuInput.io.HWRITEIn := io.LSUHWRITE
  lsuInput.io.HSIZEIn := io.LSUHSIZE
  lsuInput.io.HBURSTIn := io.LSUHBURST
  lsuInput.io.HADDRIn := io.LSUHADDR
  lsuInput.io.HREADYIn := io.HREADY &&
    (!LSUPending || DataOwner === LSUOwner.U(2.W))
  io.LSUHREADY := lsuInput.io.HREADYOut

  io.HADDR := Mux(arb.io.LSUSelect, lsuInput.io.HADDROut,
    Mux(arb.io.IFUSelect, ifuInput.io.HADDROut, 0.U(34.W)))
  io.HSIZE := Mux(arb.io.LSUSelect, lsuInput.io.HSIZEOut,
    Mux(arb.io.IFUSelect, ifuInput.io.HSIZEOut, 0.U(3.W)))
  io.HTRANS := Mux(arb.io.LSUSelect, lsuInput.io.HTRANSOut,
    Mux(arb.io.IFUSelect, ifuInput.io.HTRANSOut, 0.U(2.W)))
  io.HWRITE := arb.io.LSUSelect && lsuInput.io.HWRITEOut
  io.HBURST := 0.U(3.W)
  io.HPROT := Mux(arb.io.LSUSelect, 3.U(4.W), 2.U(4.W))
  io.HMASTLOCK := false.B
  io.HWDATA := io.LSUHWDATA
  io.HWSTRB := io.LSUHWSTRB
  io.IFUHRDATA := io.HRDATA
  io.LSUHRDATA := io.HRDATA
  io.HCLK := clk
  io.HRESETn := !reset

  // 更新归属仅依赖外部已接受地址；无地址时下一拍没有实际 Data。
  when(io.HREADY) {
    DataOwner := Mux(io.HTRANS(1),
      Mux(arb.io.LSUSelect, LSUOwner.U(2.W), IFUOwner.U(2.W)), NoOwner.U(2.W))
  }
  when(io.IFUHTRANS(1) && io.IFUHREADY) {
    IFUPending := true.B
  }.elsewhen(io.HREADY && DataOwner === IFUOwner.U(2.W)) {
    IFUPending := false.B
  }
  when(io.LSUHTRANS(1) && io.LSUHREADY) {
    LSUPending := true.B
  }.elsewhen(io.HREADY && DataOwner === LSUOwner.U(2.W)) {
    LSUPending := false.B
  }
}
