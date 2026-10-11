package riscvpipeline.lsu

import chisel3._
import chisel3.util.MuxLookup
import riscvpipeline.config.CpuConfig
import riscvpipeline.control.ControlEncoding._
import riscvpipeline.isa.Architecture._
import riscvpipeline.isa.InstructionEncoding._
import riscvpipeline.privileged.EndianSwap

/** E 级同步读请求与 M 级写请求；ReadDataM 与 M 级子字信息对齐，供下一沿写回。 */
final class LSUIO(config: CpuConfig) extends Bundle {
  val MemReadE = Input(Bool())
  val SuppressM = Input(Bool())
  val BigEndianM = Input(Bool())
  val Funct3E = Input(UInt(FUNCT3_WIDTH.W))
  val IEUAdrE = Input(UInt(config.xlen.W))
  val MemRWM = Input(UInt(MEM_WIDTH.W))
  val Funct3M = Input(UInt(FUNCT3_WIDTH.W))
  val IEUAdrM = Input(UInt(config.xlen.W))
  val WriteDataM = Input(UInt(config.xlen.W))
  val ReadDataM = Output(UInt(config.xlen.W))
  val ByteMaskM = Output(UInt(BYTES_PER_WORD.W))
  val LoadMisalignedFaultM = Output(Bool())
  val StoreAmoMisalignedFaultM = Output(Bool())
  val LoadAccessFaultM = Output(Bool())
  val StoreAmoAccessFaultM = Output(Bool())
}

/** 教材 §7.2.1/图 7.13 的同步 LSU：DTIM E 级提供读地址，M 级提供写地址和数据。
  * RV32 支持 LB/LH/LW/LBU/LHU 和 SB/SH/SW；非法或未对齐读零、不写。
  * RAM 不复位；reset 立即屏蔽请求，并同步清除读响应有效位。
  */
final class LSU(val config: CpuConfig = CpuConfig()) extends RawModule {
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new LSUIO(config))

  val ByteOffsetE = io.IEUAdrE(1, 0)
  val LoadAllowedE = MuxLookup(io.Funct3E, false.B)(Seq(
    LOAD_BYTE.U(FUNCT3_WIDTH.W) -> true.B,
    LOAD_HALF.U(FUNCT3_WIDTH.W) -> !ByteOffsetE(0),
    LOAD_WORD.U(FUNCT3_WIDTH.W) -> (ByteOffsetE === 0.U(WORD_ALIGNMENT_BITS.W)),
    LOAD_BYTE_U.U(FUNCT3_WIDTH.W) -> true.B,
    LOAD_HALF_U.U(FUNCT3_WIDTH.W) -> !ByteOffsetE(0)
  ))
  private val RAM_CAPACITY = BigInt(config.dmemDepth) * BYTES_PER_WORD
  val InRangeE = io.IEUAdrE.pad(XLEN + 1) < RAM_CAPACITY.U((XLEN + 1).W)
  val InRangeM = io.IEUAdrM.pad(XLEN + 1) < RAM_CAPACITY.U((XLEN + 1).W)
  val MisalignedM = MuxLookup(io.Funct3M, false.B)(Seq(
    LOAD_HALF.U(FUNCT3_WIDTH.W) -> io.IEUAdrM(0),
    LOAD_HALF_U.U(FUNCT3_WIDTH.W) -> io.IEUAdrM(0),
    LOAD_WORD.U(FUNCT3_WIDTH.W) -> io.IEUAdrM(1, 0).orR
  ))
  val FaultActiveM = config.privilegedSupported.B && !reset
  io.LoadMisalignedFaultM := FaultActiveM && io.MemRWM(MEM_READ_BIT) && MisalignedM
  io.StoreAmoMisalignedFaultM := FaultActiveM && io.MemRWM(MEM_WRITE_BIT) && MisalignedM
  io.LoadAccessFaultM := FaultActiveM && io.MemRWM(MEM_READ_BIT) && !InRangeM
  io.StoreAmoAccessFaultM := FaultActiveM && io.MemRWM(MEM_WRITE_BIT) && !InRangeM
  val BigEndianM = config.privilegedSupported.B && io.BigEndianM
  val writeSwap = Module(new EndianSwap)
  val readSwap = Module(new EndianSwap)
  val swByteMask = Module(new SwByteMask(config.xlen))
  val subwordWrite = Module(new SubwordWrite(config.xlen))
  val dtim = Module(new DTIM(config.xlen, config.dmemDepth))
  val subwordRead = Module(new SubwordRead(config.xlen))

  swByteMask.io.Funct3 := io.Funct3M
  swByteMask.io.ByteOffset := io.IEUAdrM(1, 0)
  subwordWrite.io.Funct3 := io.Funct3M
  subwordWrite.io.WriteData := io.WriteDataM
  val WriteAllowedM = io.MemRWM(MEM_WRITE_BIT) && swByteMask.io.ByteMask.orR && !reset &&
    !io.SuppressM && (!config.privilegedSupported.B || InRangeM)
  val ReadAllowedE = io.MemReadE && LoadAllowedE && !WriteAllowedM && !reset &&
    (!config.privilegedSupported.B || InRangeE)
  val ReadValidM = withClockAndReset(clk, reset) { RegNext(ReadAllowedE, false.B) }
  dtim.clk := clk
  dtim.io.Adr := Mux(WriteAllowedM, io.IEUAdrM, io.IEUAdrE)
  dtim.io.MemRead := ReadAllowedE
  dtim.io.MemWrite := WriteAllowedM
  dtim.io.ByteMask := swByteMask.io.ByteMask
  writeSwap.io.a := subwordWrite.io.WriteDataWord
  writeSwap.io.BigEndianM := BigEndianM
  dtim.io.WriteDataWord := writeSwap.io.y

  readSwap.io.a := dtim.io.ReadDataWord
  readSwap.io.BigEndianM := BigEndianM
  subwordRead.io.ReadDataWord := readSwap.io.y
  val PhysicalOffsetM = io.IEUAdrM(1, 0)
  val BigEndianOffsetM = MuxLookup(io.Funct3M, PhysicalOffsetM)(Seq(
    LOAD_BYTE.U(FUNCT3_WIDTH.W) ->
      (PhysicalOffsetM ^ ENDIAN_BYTE_OFFSET_XOR.U(WORD_ALIGNMENT_BITS.W)),
    LOAD_BYTE_U.U(FUNCT3_WIDTH.W) ->
      (PhysicalOffsetM ^ ENDIAN_BYTE_OFFSET_XOR.U(WORD_ALIGNMENT_BITS.W)),
    LOAD_HALF.U(FUNCT3_WIDTH.W) ->
      (PhysicalOffsetM ^ ENDIAN_HALF_OFFSET_XOR.U(WORD_ALIGNMENT_BITS.W)),
    LOAD_HALF_U.U(FUNCT3_WIDTH.W) ->
      (PhysicalOffsetM ^ ENDIAN_HALF_OFFSET_XOR.U(WORD_ALIGNMENT_BITS.W))
  ))
  subwordRead.io.ByteOffset := Mux(BigEndianM, BigEndianOffsetM, PhysicalOffsetM)
  subwordRead.io.Funct3 := io.Funct3M
  io.ReadDataM := Mux(ReadValidM && !reset, subwordRead.io.ReadData, 0.U(config.xlen.W))
  io.ByteMaskM := Mux(WriteAllowedM, swByteMask.io.ByteMask, 0.U(BYTES_PER_WORD.W))
}
