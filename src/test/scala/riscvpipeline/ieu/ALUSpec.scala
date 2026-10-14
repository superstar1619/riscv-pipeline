package riscvpipeline.ieu

import riscvpipeline.TestEncoding.{ALUFunction, Control}
import chisel3._
import chisel3.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.Files
import scala.util.Random

// Only this test wrapper has clock/reset; the production ALU is combinational.
private class ALUHarness(dataWidth: Int) extends Module {
  val io = IO(new ALUIO(dataWidth))
  val dut = Module(new ALU(dataWidth))
  dut.io.SrcA := io.SrcA
  dut.io.SrcB := io.SrcB
  dut.io.ALUControl := io.ALUControl
  dut.io.Funct3 := io.Funct3
  io.ALUResult := dut.io.ALUResult
  io.IEUAdr := dut.io.IEUAdr
}

class ALUSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  // 16 forced-arithmetic settings plus 14 ALU-mode settings. Both comparisons
  // require SubArith=1; other ALU functions exercise either SubArith value.
  private val cases = (for (control <- Seq(Control.ALU_ADD,
    Control.ALU_SUBTRACT); funct3 <- 0 until 8)
    yield (control, funct3)) ++ (for (control <- Seq(Control.ALU_FUNCTION,
      Control.ALU_FUNCTION_SUBTRACT); funct3 <- 0 until 8
      if control == Control.ALU_FUNCTION_SUBTRACT || (funct3 != ALUFunction.SLT &&
        funct3 != ALUFunction.SLTU)) yield (control, funct3))

  private def signed(value: BigInt, dataWidth: Int): BigInt =
    if (value.testBit(dataWidth - 1)) value - (BigInt(1) << dataWidth) else value

  private def check(
    dut: ALUHarness,
    dataWidth: Int,
    a: BigInt,
    b: BigInt,
    control: Int,
    funct3: Int
  ): Unit = {
    val mask = (BigInt(1) << dataWidth) - 1
    val subtract = (control & Control.ALU_SUBTRACT) != 0
    val address = (if (subtract) a - b else a + b) & mask
    val function = if ((control & Control.ALU_FUNCTION) != 0) funct3 else ALUFunction.ADD_SUB
    // Independent arithmetic model: subtraction uses BigInt subtraction,
    // and signed slt directly compares signed numbers, without RTL overflow logic.
    val amountBits = 32 - Integer.numberOfLeadingZeros(dataWidth - 1)
    val amount = (b & ((BigInt(1) << amountBits) - 1)).toInt
    val result = function match {
      case ALUFunction.ADD_SUB => address
      case ALUFunction.SLL => (a << amount) & mask
      case ALUFunction.SLT =>
        require(subtract, "Signed slt requires Sub=1, matching the book's controller")
        if (signed(a, dataWidth) < signed(b, dataWidth)) BigInt(1) else BigInt(0)
      case ALUFunction.SLTU =>
        require(subtract, "Unsigned slt requires SubArith=1, matching the book's controller")
        if (a < b) BigInt(1) else BigInt(0)
      case ALUFunction.XOR => a ^ b
      case ALUFunction.SR => (if (subtract) signed(a,
        dataWidth) >> amount else a >> amount) & mask
      case ALUFunction.OR => a | b
      case ALUFunction.AND => a & b
    }
    expectOutputs(dut, a, b, control, funct3, result, address)
  }

  private def expectOutputs(
    dut: ALUHarness,
    a: BigInt,
    b: BigInt,
    control: Int,
    funct3: Int,
    result: BigInt,
    address: BigInt
  ): Unit = {
    dut.io.SrcA.poke(a.U)
    dut.io.SrcB.poke(b.U)
    dut.io.ALUControl.poke(control.U)
    dut.io.Funct3.poke(funct3.U)
    dut.io.ALUResult.expect(result.U)
    dut.io.IEUAdr.expect(address.U)
  }

  behavior of "ALU"

  for (dataWidth <- Seq(32, 64)) {
    it should s"handle wraparound and signed overflow boundaries at $dataWidth bits" in {
      test(new ALUHarness(dataWidth)) { dut =>
        val mask = (BigInt(1) << dataWidth) - 1
        val signBit = BigInt(1) << (dataWidth - 1)
        val alternating = (0 until dataWidth by 2).foldLeft(BigInt(0))(_.setBit(_))
        val patterns = Seq(BigInt(0), BigInt(1), mask, signBit,
          signBit - 1, signBit + 1, alternating)
        for (a <- patterns; b <- patterns; (control, funct3) <- cases) {
          check(dut, dataWidth, a, b, control, funct3)
        }
      }
    }

    it should (s"match independent arithmetic and both outputs on random " +
      s"$dataWidth-bit operands") in {
      test(new ALUHarness(dataWidth)) { dut =>
        val random = new Random(0x215A1L + dataWidth)
        for (_ <- 0 until 500) {
          val a = BigInt(dataWidth, random)
          val b = BigInt(dataWidth, random)
          for ((control, funct3) <- cases) {
            check(dut, dataWidth, a, b, control, funct3)
          }
        }
      }
    }

    it should s"perform all ten register and nine immediate operations at $dataWidth bits" in {
      test(new ALUHarness(dataWidth)) { dut =>
        val mask = (BigInt(1) << dataWidth) - 1
        val signBit = BigInt(1) << (dataWidth - 1)
        // Hand-checked results use operand/control settings at the ALU boundary;
        // immediate operands here have already been extended by Extend.
        val operations = Seq(
          ("ADD", BigInt(5), BigInt(7), Control.ALU_FUNCTION, ALUFunction.ADD_SUB,
            BigInt(12), BigInt(12)),
          ("SUB", BigInt(5), BigInt(7), Control.ALU_FUNCTION_SUBTRACT, ALUFunction.ADD_SUB,
            mask - 1, mask - 1),
          ("SLL", BigInt(3), BigInt(4), Control.ALU_FUNCTION, ALUFunction.SLL, BigInt(48),
            BigInt(7)),
          ("SLT", signBit, signBit - 1, Control.ALU_FUNCTION_SUBTRACT, ALUFunction.SLT,
            BigInt(1), BigInt(1)),
          ("SLTU", BigInt(0), mask, Control.ALU_FUNCTION_SUBTRACT, ALUFunction.SLTU,
            BigInt(1), BigInt(1)),
          ("XOR", BigInt(0x55), BigInt(0xf), Control.ALU_FUNCTION, ALUFunction.XOR,
            BigInt(0x5a), BigInt(0x64)),
          ("SRL", signBit, BigInt(1), Control.ALU_FUNCTION, ALUFunction.SR, signBit >> 1,
            signBit + 1),
          ("SRA", signBit, BigInt(1), Control.ALU_FUNCTION_SUBTRACT, ALUFunction.SR,
            signBit | (signBit >> 1), signBit - 1),
          ("OR", BigInt(0x55), BigInt(0xf), Control.ALU_FUNCTION, ALUFunction.OR,
            BigInt(0x5f), BigInt(0x64)),
          ("AND", BigInt(0x55), BigInt(0xf), Control.ALU_FUNCTION, ALUFunction.AND,
            BigInt(5), BigInt(0x64)),
          ("ADDI", BigInt(5), mask, Control.ALU_FUNCTION, ALUFunction.ADD_SUB, BigInt(4),
            BigInt(4)),
          ("SLLI", BigInt(3), BigInt(4), Control.ALU_FUNCTION, ALUFunction.SLL, BigInt(48),
            BigInt(7)),
          ("SLTI", BigInt(0), mask, Control.ALU_FUNCTION_SUBTRACT, ALUFunction.SLT,
            BigInt(0), BigInt(1)),
          ("SLTIU", BigInt(0), mask, Control.ALU_FUNCTION_SUBTRACT, ALUFunction.SLTU,
            BigInt(1), BigInt(1)),
          ("XORI", BigInt(0x55), mask, Control.ALU_FUNCTION, ALUFunction.XOR, mask ^ 0x55,
            BigInt(0x54)),
          ("SRLI", signBit, BigInt(1), Control.ALU_FUNCTION, ALUFunction.SR, signBit >> 1,
            signBit + 1),
          ("SRAI", signBit, BigInt(1), Control.ALU_FUNCTION_SUBTRACT, ALUFunction.SR,
            signBit | (signBit >> 1), signBit - 1),
          ("ORI", BigInt(0x55), mask, Control.ALU_FUNCTION, ALUFunction.OR, mask,
            BigInt(0x54)),
          ("ANDI", BigInt(0x55), mask, Control.ALU_FUNCTION, ALUFunction.AND, BigInt(0x55),
            BigInt(0x54))
        )
        for ((name, a, b, control, funct3, result, address) <- operations) {
          withClue(name) { expectOutputs(dut, a, b, control, funct3, result, address) }
        }
      }
    }

    it should (s"shift by every amount while ignoring high SrcB bits only for shifts " +
      s"at $dataWidth bits") in {
      test(new ALUHarness(dataWidth)) { dut =>
        val mask = (BigInt(1) << dataWidth) - 1
        val signBit = BigInt(1) << (dataWidth - 1)
        val alternating = (0 until dataWidth by 2).foldLeft(BigInt(0))(_.setBit(_))
        val patterns = Seq(BigInt(0), BigInt(1), mask, signBit, signBit - 1,
          signBit | 1, alternating, mask ^ alternating)
        val highBits = mask ^ BigInt(dataWidth - 1)
        for (a <- patterns; amount <- 0 until dataWidth;
            b <- Seq(BigInt(amount), BigInt(amount) | highBits);
            (control, funct3) <- Seq((Control.ALU_FUNCTION, ALUFunction.SLL),
              (Control.ALU_FUNCTION_SUBTRACT, ALUFunction.SLL),
              (Control.ALU_FUNCTION, ALUFunction.SR),
              (Control.ALU_FUNCTION_SUBTRACT, ALUFunction.SR))) {
          check(dut, dataWidth, a, b, control, funct3)
        }
      }
    }
  }

  for (dataWidth <- Seq(1, 4, 3, 5)) {
    it should (s"match all operand pairs for the supported control cases at $dataWidth " +
      s"bits") in {
      test(new ALUHarness(dataWidth)) { dut =>
        for (a <- 0 until (1 << dataWidth); b <- 0 until (1 << dataWidth);
            (control, funct3) <- cases) {
          check(dut, dataWidth, BigInt(a), BigInt(b), control, funct3)
        }
      }
    }
  }

  it should "elaborate exactly the six combinational book ports at 32 and 64 bits" in {
    for (dataWidth <- Seq(32, 64)) {
      val targetDir = Files.createTempDirectory("alu-interface-")
      val verilog = (new ChiselStage).emitVerilog(new ALU(dataWidth),
        Array("--target-dir", targetDir.toString))
      val top = "(?s)module ALU\\(.*?\\n\\);".r.findFirstIn(verilog).get
      val portPattern = "(?m)^\\s*(input|output)\\s+(?:\\[(\\d+):0\\]\\s+)?(\\w+)".r
      val ports = portPattern.findAllMatchIn(top).map { port =>
        val width = Option(port.group(2)).map(_.toInt + 1).getOrElse(1)
        port.group(3) -> (port.group(1), width)
      }.toMap
      assert(ports == Map("io_SrcA" -> ("input", dataWidth),
        "io_SrcB" -> ("input", dataWidth), "io_ALUControl" -> ("input", 2),
        "io_Funct3" -> ("input", 3), "io_ALUResult" -> ("output", dataWidth),
        "io_IEUAdr" -> ("output", dataWidth)))
      assert(!verilog.contains("always @"))
      assert(verilog.contains("Shifter shifter ("))
      assert(verilog.contains(s"input  [${dataWidth - 1}:0] io_A,"))
      assert(verilog.contains(".io_A(shifter_io_A)"))
      assert(verilog.contains(".io_Amt(shifter_io_Amt)"))
      assert(verilog.contains(".io_Right(shifter_io_Right)"))
      assert(verilog.contains(".io_SubArith(shifter_io_SubArith)"))
      assert(verilog.contains(".io_Y(shifter_io_Y)"))
    }
  }

  it should "reject nonpositive operand widths at elaboration" in {
    for (dataWidth <- Seq(-1, 0)) {
      val error = intercept[IllegalArgumentException] {
        (new ChiselStage).emitChirrtl(new ALU(dataWidth))
      }
      assert(error.getMessage.contains("ALU dataWidth must be > 0"))
    }
  }
}
