package riscvpipeline.privileged

import chisel3._
import chisel3.util.Cat
import riscvpipeline.isa.Architecture._

/** 固定 RV32 整字的数据端序转换；保留 CVW 通用端口 a/y。 */
final class EndianSwapIO extends Bundle {
  val BigEndianM = Input(Bool())
  val a = Input(UInt(XLEN.W))
  val y = Output(UInt(XLEN.W))
}

/** 教材 §8.4.7：颠倒四个字节的排列，字节内部位顺序保持不变。
  * 纯组合 RawModule，无时钟、复位或状态；子字偏移与写掩码由 LSU 处理。
  */
final class EndianSwap extends RawModule {
  val io = IO(new EndianSwapIO)

  val Swapped = Cat((0 until BYTES_PER_WORD).map { byte =>
    io.a((byte + 1) * BYTE_WIDTH - 1, byte * BYTE_WIDTH)
  })
  io.y := Mux(io.BigEndianM, Swapped, io.a)
}
