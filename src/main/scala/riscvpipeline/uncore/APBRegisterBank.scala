package riscvpipeline.uncore

import chisel3._
import riscvpipeline.ebu.APBMasterIO

final class APBRegisterBankIO extends Bundle {
  val apb = Flipped(new APBMasterIO)
}

/** 第九章验证用最小 APB 从设备，不替代第20章外设。
  * PADDR 是相对窗口的完整字节偏移，offset0 scratch、offset4 只读写完成计数。
  * 每个完成的写 ACCESS 均计数（含零掩码、只读及未定义地址写）。
  * Setup 无副作用，完成后持续拉住 Access 也不会重复写。Bool 同步复位。
  */
final class APBRegisterBank(extraLatency: Int = 0) extends RawModule {
  require(extraLatency >= 0 && extraLatency <= 255)
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new APBRegisterBankIO)
  val Scratch = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val WriteCompletions = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val Wait = withClockAndReset(clk, reset) { RegInit(0.U(8.W)) }
  val Completed = withClockAndReset(clk, reset) { RegInit(false.B) }
  val Access = io.apb.PSEL && io.apb.PENABLE
  val Complete = Access && io.apb.PREADY && !Completed
  // 寄存器按32位字译码，低两位仅由 PSTRB 选择字节；上位偏移完整保留。
  val RegisterIndex = io.apb.PADDR(31, 2)
  io.apb.PREADY := Wait === 0.U
  io.apb.PRDATA := Mux(RegisterIndex === 0.U, Scratch,
    Mux(RegisterIndex === 1.U, WriteCompletions, 0.U(32.W)))

  when(!io.apb.PSEL || !io.apb.PENABLE) {
    Completed := false.B
    Wait := extraLatency.U
  }.elsewhen(Wait =/= 0.U) {
    Wait := Wait - 1.U
  }
  when(Complete) {
    Completed := true.B
    when(io.apb.PWRITE) {
      WriteCompletions := WriteCompletions + 1.U
    }
  }
  val UpdatedBytes = Wire(Vec(4, UInt(8.W)))
  for (lane <- 0 until 4) {
    UpdatedBytes(lane) := Mux(io.apb.PSTRB(lane),
      io.apb.PWDATA(8 * lane + 7, 8 * lane), Scratch(8 * lane + 7, 8 * lane))
  }
  when(Complete && io.apb.PWRITE && RegisterIndex === 0.U) {
    Scratch := UpdatedBytes.asUInt
  }
}
