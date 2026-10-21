package riscvpipeline.lsu

import chisel3._
import chisel3.util.log2Ceil
import riscvpipeline.config.CpuConfig
import riscvpipeline.isa.Architecture._

/** 共享字节地址、原生字数据与逐字节写使能；ByteMask(0) 对应最低字节。 */
final class DTIMIO(dataWidth: Int) extends Bundle {
  val Adr = Input(UInt(dataWidth.W))
  val MemRead = Input(Bool())
  val MemWrite = Input(Bool())
  val WriteDataWord = Input(UInt(dataWidth.W))
  val ByteMask = Input(UInt((dataWidth / BYTE_WIDTH).W))
  val ReadDataWord = Output(UInt(dataWidth.W))
}

/** 教材 §7.2.1 的同步数据 RAM，参考 CVW dtim.sv 的共享地址及字节写使能。
  * 读写共用 Adr，写优先；读数据在采样沿后有效一周期，无请求周期输出零。
  * depth 以原生字计，地址按容量回绕；没有复位或初始化，未写字节未定义。
  */
final class DTIM(
  val dataWidth: Int = XLEN,
  val depth: Int = CpuConfig.DEFAULT_DMEM_DEPTH
) extends RawModule {
  require(dataWidth == XLEN || dataWidth == 64, "DTIM dataWidth must be 32 or 64")
  require(depth >= 2 && (depth & (depth - 1)) == 0,
    "DTIM depth must be a power of two >= 2")
  require(BigInt(depth) * (dataWidth / BYTE_WIDTH) <= (BigInt(1) << XLEN),
    "DTIM capacity must not exceed 2^32 bytes")

  val clk = IO(Input(Clock()))
  val io = IO(new DTIMIO(dataWidth))
  private val byteCount = dataWidth / BYTE_WIDTH
  private val byteOffsetWidth = log2Ceil(byteCount)
  private val indexWidth = log2Ceil(depth)
  val WordIndex = io.Adr(indexWidth + byteOffsetWidth - 1, byteOffsetWidth)
  val RAM = withClock(clk) { SyncReadMem(depth, Vec(byteCount, UInt(BYTE_WIDTH.W))) }
  val ReadEnable = io.MemRead && !io.MemWrite
  // 物理读端口常开；请求有效位只控制对外响应。
  val ReadBytes = RAM.read(WordIndex, true.B, clk)
  val ReadIssued = withClock(clk) { RegNext(ReadEnable) }
  io.ReadDataWord := Mux(ReadIssued, ReadBytes.asUInt, 0.U(dataWidth.W))

  val WriteBytes = Wire(Vec(byteCount, UInt(BYTE_WIDTH.W)))
  for (lane <- 0 until byteCount) {
    WriteBytes(lane) := io.WriteDataWord(BYTE_WIDTH * (lane + 1) - 1, BYTE_WIDTH * lane)
  }
  when(io.MemWrite && io.ByteMask.orR) {
    RAM.write(WordIndex, WriteBytes, io.ByteMask.asBools, clk)
  }
}
