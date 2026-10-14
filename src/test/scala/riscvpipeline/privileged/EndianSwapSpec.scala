package riscvpipeline.privileged

import chisel3._
import chisel3.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.Files
import scala.util.Random

private class EndianSwapHarness extends Module {
  val io = IO(new EndianSwapIO)
  val dut = Module(new EndianSwap)
  io <> dut.io
}

class EndianSwapSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  private def check(d: EndianSwapHarness, data: BigInt, big: Boolean,
                    expected: BigInt): Unit = {
    d.io.a.poke(data.U)
    d.io.BigEndianM.poke(big.B)
    d.io.y.expect(expected.U)
  }

  // 软件先按大端字节序编码，再用小端序解码；不调用生产助手。
  private def reversed(data: BigInt): BigInt = {
    val bytes = ByteBuffer.allocate(4).putInt(data.toInt).array()
    BigInt(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt().toLong & 0xffffffffL)
  }

  behavior of "EndianSwap"

  it should "preserve little-endian words and swap four whole bytes when enabled" in {
    test(new EndianSwapHarness) { d =>
      val cases = Seq("00000000" -> "00000000", "ffffffff" -> "ffffffff",
        "01234567" -> "67452301", "80ff7f01" -> "017fff80",
        "12345678" -> "78563412", "00000001" -> "01000000",
        "80000000" -> "00000080", "a5a5a5a5" -> "a5a5a5a5")
      for ((input, swapped) <- cases) {
        val data = BigInt(input, 16)
        check(d, data, big = false, data)
        check(d, data, big = true, BigInt(swapped, 16))
      }
    }
  }

  it should "move every one-hot bit to the opposite byte without reversing its bit index" in {
    test(new EndianSwapHarness) { d =>
      for (lane <- 0 until 4; bit <- 0 until 8) {
        val data = BigInt(1) << (lane * 8 + bit)
        val swapped = BigInt(1) << ((3 - lane) * 8 + bit)
        check(d, data, big = false, data)
        check(d, data, big = true, swapped)
      }
    }
  }

  it should "update data and selection without a clock edge or remembered previous word" in {
    test(new EndianSwapHarness) { d =>
      check(d, BigInt("01234567", 16), big = true, BigInt("67452301", 16))
      d.io.BigEndianM.poke(false.B)
      d.io.y.expect("h01234567".U)
      d.io.a.poke("h89abcdef".U)
      d.io.y.expect("h89abcdef".U)
      d.io.BigEndianM.poke(true.B)
      d.io.y.expect("hefcdab89".U)
      d.reset.poke(true.B)
      d.io.y.expect("hefcdab89".U)
      d.io.a.poke(0.U)
      d.io.y.expect(0.U)
    }
  }

  it should "match an independent byte-codec reference for seeded full-width words" in {
    test(new EndianSwapHarness) { d =>
      val random = new Random(0xe11d1aL)
      for (_ <- 0 until 1024) {
        val data = BigInt(32, random)
        check(d, data, big = false, data)
        check(d, data, big = true, reversed(data))
      }
    }
  }

  it should "restore the input when swapping twice" in {
    test(new EndianSwapHarness) { d =>
      val random = new Random(0x2e11d1aL)
      for (_ <- 0 until 128) {
        val data = BigInt(32, random)
        check(d, data, big = true, reversed(data))
        val swapped = d.io.y.peek().litValue
        check(d, swapped, big = true, data)
      }
    }
  }

  it should "emit exactly three combinational ports with no clock reset register or latch" in {
    val rtl = (new ChiselStage).emitVerilog(new EndianSwap,
      Array("--target-dir", Files.createTempDirectory("endian-swap-rtl-").toString))
    val pattern = "(?m)^\\s*(input|output)\\s+(?:\\[(\\d+):0\\]\\s+)?(\\w+)".r
    val ports = pattern.findAllMatchIn(rtl).map { m =>
      m.group(3) -> (m.group(1), Option(m.group(2)).map(_.toInt + 1).getOrElse(1))
    }.toMap
    assert(ports == Map("io_BigEndianM" -> ("input", 1), "io_a" -> ("input", 32),
      "io_y" -> ("output", 32)))
    assert(!rtl.contains("always @") && !rtl.contains("reg "))
  }
}
