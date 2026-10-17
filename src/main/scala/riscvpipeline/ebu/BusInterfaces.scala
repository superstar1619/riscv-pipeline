package riscvpipeline.ebu

import chisel3._

/** 固定协议编码保持 Scala 值，避免跨模块共享硬件绑定。 */
object BusConstants {
  val ADDRESS_WIDTH = 34
  val DATA_WIDTH = 32
  val STRB_WIDTH = 4
  val HTRANS_IDLE = 0
  val HTRANS_NONSEQ = 2
}

/** 单次 AHB-Lite 主接口；地址字节寻址，HWSTRB 对应四个小端字节通道。 */
final class AHBMasterIO extends Bundle {
  val HADDR = Output(UInt(34.W))
  val HWDATA = Output(UInt(32.W))
  val HWSTRB = Output(UInt(4.W))
  val HWRITE = Output(Bool())
  val HSIZE = Output(UInt(3.W))
  val HBURST = Output(UInt(3.W))
  val HPROT = Output(UInt(4.W))
  val HTRANS = Output(UInt(2.W))
  val HMASTLOCK = Output(Bool())
  val HRDATA = Input(UInt(32.W))
  val HREADY = Input(Bool())
  val HRESP = Input(Bool())
}

final class AHBSlaveIO extends Bundle {
  val HSEL = Input(Bool())
  val HADDR = Input(UInt(34.W))
  val HWDATA = Input(UInt(32.W))
  val HWSTRB = Input(UInt(4.W))
  val HWRITE = Input(Bool())
  val HSIZE = Input(UInt(3.W))
  val HTRANS = Input(UInt(2.W))
  val HREADY = Input(Bool())
  val HRDATA = Output(UInt(32.W))
  val HREADYOUT = Output(Bool())
  val HRESP = Output(Bool())
}

final class APBMasterIO extends Bundle {
  val PADDR = Output(UInt(32.W))
  val PWDATA = Output(UInt(32.W))
  val PSTRB = Output(UInt(4.W))
  val PWRITE = Output(Bool())
  val PSEL = Output(Bool())
  val PENABLE = Output(Bool())
  val PRDATA = Input(UInt(32.W))
  val PREADY = Input(Bool())
}
