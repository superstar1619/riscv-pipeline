package riscvpipeline

import riscvpipeline.TestEncoding.{Mask, MemoryFunction, Opcode}
import chisel3._
import chiseltest._
import chiseltest.experimental.expose
import riscvpipeline.config.CpuConfig
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

private[riscvpipeline] class PipelineHarness(config: CpuConfig) extends Module {
  val io = IO(new RiscvPipelineIO(config))
  val dut = Module(new RiscvPipeline(config))
  dut.clk := clock
  dut.reset := reset.asBool
  dut.io <> io
  val PCF = expose(dut.ifu.io.PCF)
  val PCD = expose(dut.ifu.io.PCD)
  val InstrD = expose(dut.ifu.io.InstrD)
  val ValidD = expose(dut.ifu.io.ValidD)
  val ValidE = expose(dut.ieu.dp.io.ValidE)
  val ValidM = expose(dut.ieu.dp.io.ValidM)
  val ValidW = expose(dut.ieu.dp.io.ValidW)
  val StallD = expose(dut.ieu.io.StallD)
  val LoadStallD = expose(dut.ieu.hazard.io.LoadStallD)
  val StoreStallD = expose(dut.ieu.hazard.io.StoreStallD)
  val Register9 = expose(dut.ieu.dp.rf.Registers(9))
}

private[riscvpipeline] object PipelineTestSupport {
  val NOP: BigInt = 0x13
  def addi(rd: Int, rs1: Int, imm: Int): BigInt =
    (BigInt(imm & Mask.IMM12) << 20) | (BigInt(rs1) << 15) | (BigInt(rd) << 7) | Opcode.OP_IMM
  def load(rd: Int, rs1: Int, imm: Int, funct3: Int = MemoryFunction.WORD): BigInt =
    (BigInt(imm & Mask.IMM12) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | Opcode.LOAD
  def store(rs2: Int, address: Int, funct3: Int = MemoryFunction.WORD,
    rs1: Int = 0): BigInt = {
    val imm = address & Mask.IMM12
    (BigInt(imm >> 5) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(imm & Mask.REGISTER_FIELD) << 7) | Opcode.STORE
  }
  def jal(rd: Int, offset: Int): BigInt = {
    val imm = offset & Mask.JUMP_IMMEDIATE
    (BigInt((imm >> 20) & 1) << 31) | (BigInt((imm >> 1) & 1023) << 21) |
      (BigInt((imm >> 11) & 1) << 20) | (BigInt((imm >> 12) & 255) << 12) |
      (BigInt(rd) << 7) | Opcode.JAL
  }
  def branch(rs1: Int, rs2: Int, offset: Int, funct3: Int = 0): BigInt = {
    val imm = offset & Mask.BRANCH_IMMEDIATE
    (BigInt((imm >> 12) & 1) << 31) | (BigInt((imm >> 5) & 63) << 25) |
      (BigInt(rs2) << 20) | (BigInt(rs1) << 15) | (BigInt(funct3) << 12) |
      (BigInt((imm >> 1) & 15) << 8) | (BigInt((imm >> 11) & 1) << 7) | Opcode.BRANCH
  }
  def image(words: Seq[BigInt], resetVector: Int = 0): CpuConfig = {
    val depth = if (resetVector == 0) 64 else 128
    require(words.length + resetVector / 4 <= depth)
    val directory = Files.createDirectories(Paths.get("target/pipeline-images"))
    val file = Files.createTempFile(directory, "program-", ".hex")
    val contents = Seq.fill(resetVector / 4)(NOP) ++ words ++
      Seq.fill(depth - resetVector / 4 - words.length)(NOP)
    Files.write(file, contents.map(w => f"$w%08x").mkString("", "\n", "\n")
      .getBytes(StandardCharsets.UTF_8))
    CpuConfig(imemDepth = depth, resetVector = resetVector,
      instructionInitFile = Some(file.toString))
  }
  def reset(dut: PipelineHarness): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }
  // Record the actual M write request before the edge which commits it to RAM.
  def stores(dut: PipelineHarness, cycles: Int): Seq[(Int, BigInt, BigInt)] = {
    (0 until cycles).flatMap { cycle =>
      val request = if (dut.io.MemWrite.peek().litToBoolean) {
        Some((cycle, dut.io.IEUAdr.peek().litValue, dut.io.WriteData.peek().litValue))
      } else None
      dut.clock.step()
      request
    }
  }
}
