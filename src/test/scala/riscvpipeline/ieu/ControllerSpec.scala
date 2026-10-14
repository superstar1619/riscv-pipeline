package riscvpipeline.ieu

import riscvpipeline.TestEncoding.{ALUFunction, BranchFunction, Control, Immediate, Mask,
  MemoryFunction, Opcode}
import chisel3._
import chisel3.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.Files

private class ControllerHarness extends Module {
  val io = IO(new ControllerIO)
  val dut = Module(new Controller)
  dut.io <> io
}

private class ControllerALUTestIO extends Bundle {
  val Op = Input(UInt(7.W))
  val Funct3 = Input(UInt(3.W))
  val Funct7 = Input(UInt(7.W))
  val SrcA = Input(UInt(32.W))
  val SrcB = Input(UInt(32.W))
  val ALUResult = Output(UInt(32.W))
  val IEUAdr = Output(UInt(32.W))
}

private class ControllerALUHarness extends Module {
  val io = IO(new ControllerALUTestIO)
  val controller = Module(new Controller)
  val alu = Module(new ALU)
  controller.io.Op := io.Op
  controller.io.Funct3 := io.Funct3
  controller.io.Funct7 := io.Funct7
  controller.io.Eq := false.B
  controller.io.LT := false.B
  controller.io.LTU := false.B
  alu.io.SrcA := io.SrcA
  alu.io.SrcB := io.SrcB
  alu.io.ALUControl := controller.io.ALUControl
  alu.io.Funct3 := io.Funct3
  io.ALUResult := alu.io.ALUResult
  io.IEUAdr := alu.io.IEUAdr
}

class ControllerSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  // Independent behavioral table; this reference never packs control bits.
  private case class MainControls(
    regWrite: Boolean = false,
    immSrc: Int = Immediate.I,
    aluSrc: Int = Control.SRC_REGISTERS,
    aluOp: Boolean = false,
    aluResultSrc: Boolean = false,
    memRW: Int = Control.MEM_NONE,
    resultSrc: Boolean = false,
    branch: Boolean = false,
    jump: Boolean = false
  )
  private val mainTable = Map(
    Opcode.OP -> MainControls(regWrite = true, aluOp = true),
    Opcode.OP_IMM -> MainControls(regWrite = true, aluSrc = Control.SRC_IMMEDIATE,
      aluOp = true),
    Opcode.LOAD -> MainControls(regWrite = true, aluSrc = Control.SRC_IMMEDIATE,
      memRW = Control.MEM_READ, resultSrc = true),
    Opcode.STORE -> MainControls(immSrc = Immediate.S, aluSrc = Control.SRC_IMMEDIATE,
      memRW = Control.MEM_WRITE),
    Opcode.BRANCH -> MainControls(immSrc = Immediate.B, aluSrc = Control.SRC_PC_IMMEDIATE,
      branch = true),
    Opcode.JAL -> MainControls(regWrite = true, immSrc = Immediate.J,
      aluSrc = Control.SRC_PC_IMMEDIATE,
      aluResultSrc = true, jump = true),
    Opcode.JALR -> MainControls(regWrite = true, aluSrc = Control.SRC_IMMEDIATE,
      aluResultSrc = true, jump = true),
    Opcode.LUI -> MainControls(regWrite = true, immSrc = Immediate.U, aluResultSrc = true),
    Opcode.AUIPC -> MainControls(regWrite = true, immSrc = Immediate.U,
      aluSrc = Control.SRC_PC_IMMEDIATE)
  )

  private def legal(op: Int, f3: Int, f7: Int): Boolean = op match {
    case Opcode.OP => f7 == 0 || (f7 == Mask.FUNCT7_ALTERNATE && Set(ALUFunction.ADD_SUB,
      ALUFunction.SR).contains(f3))
    case Opcode.OP_IMM => f3 match {
      case ALUFunction.SLL => f7 == 0
      case ALUFunction.SR => Set(0, Mask.FUNCT7_ALTERNATE).contains(f7)
      case _ => true
    }
    case Opcode.LOAD => Set(MemoryFunction.BYTE, MemoryFunction.HALF, MemoryFunction.WORD,
      MemoryFunction.BYTE_UNSIGNED, MemoryFunction.HALF_UNSIGNED).contains(f3)
    case Opcode.STORE => Set(MemoryFunction.BYTE, MemoryFunction.HALF,
      MemoryFunction.WORD).contains(f3)
    case Opcode.BRANCH => Set(BranchFunction.EQ, BranchFunction.NE, BranchFunction.LT,
      BranchFunction.GE, BranchFunction.LTU, BranchFunction.GEU).contains(f3)
    case Opcode.JALR => f3 == 0
    case Opcode.JAL | Opcode.LUI | Opcode.AUIPC => true
    case _ => false
  }

  private def check(
    dut: ControllerHarness,
    op: Int,
    f3: Int,
    f7: Int,
    flags: Int,
    expected: MainControls
  ): Unit = {
    val eq = (flags & 1) != 0
    val lt = (flags & 2) != 0
    val ltu = (flags & 4) != 0
    val subtract = expected.aluOp && (f3 == ALUFunction.SLT || f3 == ALUFunction.SLTU ||
      (f3 == ALUFunction.SR && (f7 & Mask.FUNCT7_ALTERNATE) != 0) || (op == Opcode.OP &&
        f3 == ALUFunction.ADD_SUB && (f7 & Mask.FUNCT7_ALTERNATE) != 0))
    val taken = f3 match {
      case BranchFunction.EQ => eq
      case BranchFunction.NE => !eq
      case BranchFunction.LT => lt
      case BranchFunction.GE => !lt
      case BranchFunction.LTU => ltu
      case BranchFunction.GEU => !ltu
      case _ => false
    }
    dut.io.Op.poke(op.U)
    dut.io.Funct3.poke(f3.U)
    dut.io.Funct7.poke(f7.U)
    dut.io.Eq.poke(eq.B)
    dut.io.LT.poke(lt.B)
    dut.io.LTU.poke(ltu.B)
    dut.io.RegWrite.expect(expected.regWrite.B)
    dut.io.ImmSrc.expect(expected.immSrc.U)
    dut.io.ALUSrc.expect(expected.aluSrc.U)
    dut.io.ALUResultSrc.expect(expected.aluResultSrc.B)
    dut.io.MemRW.expect(expected.memRW.U)
    dut.io.MemWrite.expect(((expected.memRW & Control.MEM_WRITE) != 0).B)
    dut.io.ResultSrc.expect(expected.resultSrc.B)
    dut.io.ALUControl.expect(((if (subtract) Control.ALU_SUBTRACT else Control.ALU_ADD) |
      (if (expected.aluOp) Control.ALU_FUNCTION else Control.ALU_ADD)).U)
    dut.io.Jump.expect(expected.jump.B)
    dut.io.PCSrc.expect((expected.jump || (expected.branch && taken)).B)
  }

  behavior of "Controller"

  it should ("match independent legality and controls for all opcode funct3 funct7 " +
    "combinations") in {
    test(new ControllerHarness) { dut =>
      for (op <- 0 until 128; f3 <- 0 until 8; f7 <- 0 until 128) {
        val expected = if (legal(op, f3, f7)) mainTable(op) else MainControls()
        check(dut, op, f3, f7, (op ^ f3 ^ f7) & 7, expected)
      }
    }
  }

  it should ("select all six branch conditions for every function field and all eight " +
    "flag combinations") in {
    test(new ControllerHarness) { dut =>
      for (f3 <- 0 until 8; f7 <- 0 until 128; flags <- 0 until 8) {
        val expected = if (legal(Opcode.BRANCH, f3,
          f7)) mainTable(Opcode.BRANCH) else MainControls()
        check(dut, Opcode.BRANCH, f3, f7, flags, expected)
      }
    }
  }

  it should ("distinguish immediate data from reserved shift and unsupported extension " +
    "encodings") in {
    test(new ControllerHarness) { dut =>
      // Each fixture states legality explicitly, independent of the sweep decoder.
      val vectors = Seq(
        (0x33, 0, 0, true), (0x33, 0, 32, true), (0x33, 5, 32, true),
        (0x33, 1, 32, false), (0x33, 7, 32, false), (0x33, 0, 1, false),
        (0x33, 4, 4, false), (0x33, 0, 64, false),
        (0x13, 0, 127, true), (0x13, 2, 127, true), (0x13, 3, 127, true),
        (0x13, 4, 127, true), (0x13, 6, 127, true), (0x13, 7, 127, true),
        (0x13, 1, 0, true), (0x13, 1, 1, false), (0x13, 1, 32, false),
        (0x13, 5, 0, true), (0x13, 5, 32, true), (0x13, 5, 1, false),
        (0x13, 5, 33, false), (0x03, 3, 127, false), (0x23, 3, 127, false),
        (0x63, 3, 127, false), (0x67, 0, 127, true), (0x67, 1, 127, false),
        (0x6f, 7, 127, true), (0x37, 7, 127, true), (0x17, 7, 127, true),
        (0x1b, 0, 0, false), (0x3b, 0, 0, false), (0x0f, 0, 0, false),
        (0x73, 1, 0, false)
      )
      for ((op, f3, f7, isLegal) <- vectors) {
        check(dut, op, f3, f7, 7, if (isLegal) mainTable(op) else MainControls())
      }
    }
  }

  it should "drive all nineteen ALU operations and six address-generation categories" in {
    test(new ControllerALUHarness) { dut =>
      // op, funct3, full funct7, a, b, result, address; literal hand-checked outputs.
      val vectors = Seq(
        (Opcode.OP, 0, 0, 5L, 7L, 12L, 12L),
        (Opcode.OP, 0, 32, 5L, 7L, 0xfffffffeL, 0xfffffffeL),
        (Opcode.OP, 1, 0, 3L, 4L, 48L, 7L),
        (Opcode.OP, 2, 0, 0x80000000L, 0x7fffffffL, 1L, 1L),
        (Opcode.OP, 3, 0, 0L, 0xffffffffL, 1L, 1L),
        (Opcode.OP, 4, 0, 0x55L, 0xfL, 0x5aL, 0x64L),
        (Opcode.OP, 5, 0, 0x80000000L, 1L, 0x40000000L, 0x80000001L),
        (Opcode.OP, 5, 32, 0x80000000L, 1L, 0xc0000000L, 0x7fffffffL),
        (Opcode.OP, 6, 0, 0x55L, 0xfL, 0x5fL, 0x64L),
        (Opcode.OP, 7, 0, 0x55L, 0xfL, 5L, 0x64L),
        (Opcode.OP_IMM, 0, 127, 5L, 0xffffffffL, 4L, 4L),
        (Opcode.OP_IMM, 1, 0, 3L, 4L, 48L, 7L),
        (Opcode.OP_IMM, 2, 127, 0L, 0xffffffffL, 0L, 1L),
        (Opcode.OP_IMM, 3, 127, 0L, 0xffffffffL, 1L, 1L),
        (Opcode.OP_IMM, 4, 127, 0x55L, 0xffffffffL, 0xffffffaaL, 0x54L),
        (Opcode.OP_IMM, 5, 0, 0x80000000L, 1L, 0x40000000L, 0x80000001L),
        (Opcode.OP_IMM, 5, 32, 0x80000000L, 1L, 0xc0000000L, 0x7fffffffL),
        (Opcode.OP_IMM, 6, 127, 0x55L, 0xffffffffL, 0xffffffffL, 0x54L),
        (Opcode.OP_IMM, 7, 127, 0x55L, 0xffffffffL, 0x55L, 0x54L),
        (Opcode.LOAD, 0, 127, 20L, 12L, 32L, 32L),
        (Opcode.STORE, 2, 127, 20L, 12L, 32L, 32L),
        (Opcode.BRANCH, 6, 127, 32L, 8L, 40L, 40L),
        (Opcode.JAL, 7, 127, 32L, 8L, 40L, 40L),
        (Opcode.JALR, 0, 127, 32L, 8L, 40L, 40L),
        (Opcode.AUIPC, 3, 127, 32L, 4096L, 4128L, 4128L)
      )
      for ((op, f3, f7, a, b, result, address) <- vectors) {
        dut.io.Op.poke(op.U)
        dut.io.Funct3.poke(f3.U)
        dut.io.Funct7.poke(f7.U)
        dut.io.SrcA.poke(BigInt(a).U)
        dut.io.SrcB.poke(BigInt(b).U)
        dut.io.ALUResult.expect(BigInt(result).U)
        dut.io.IEUAdr.expect(BigInt(address).U)
      }
    }
  }

  it should ("elaborate exactly sixteen combinational ports with full function and " +
    "comparison inputs") in {
    val targetDir = Files.createTempDirectory("controller-interface-")
    val verilog = (new ChiselStage).emitVerilog(new Controller,
      Array("--target-dir", targetDir.toString))
    val portPattern = "(?m)^\\s*(input|output)\\s+(?:\\[(\\d+):0\\]\\s+)?(\\w+)".r
    val ports = portPattern.findAllMatchIn(verilog).map { port =>
      val width = Option(port.group(2)).map(_.toInt + 1).getOrElse(1)
      port.group(3) -> (port.group(1), width)
    }.toMap
    assert(ports == Map("io_Op" -> ("input", 7), "io_Eq" -> ("input", 1),
      "io_LT" -> ("input", 1), "io_LTU" -> ("input", 1),
      "io_Funct3" -> ("input", 3), "io_Funct7" -> ("input", 7),
      "io_ALUResultSrc" -> ("output", 1), "io_ResultSrc" -> ("output", 1),
      "io_MemWrite" -> ("output", 1), "io_MemRW" -> ("output", 2),
      "io_Jump" -> ("output", 1), "io_Branch" -> ("output", 1),
      "io_PCSrc" -> ("output", 1),
      "io_RegWrite" -> ("output", 1), "io_ALUSrc" -> ("output", 2),
      "io_ImmSrc" -> ("output", 3), "io_ALUControl" -> ("output", 2)))
    assert(!verilog.contains("always @"))
  }
}
