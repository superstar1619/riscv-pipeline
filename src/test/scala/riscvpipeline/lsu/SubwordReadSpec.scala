package riscvpipeline.lsu

import riscvpipeline.TestEncoding.{Mask, MemoryFunction}
import chisel3._
import chisel3.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.Files
import scala.util.Random

// 观察时钟仅用于测试与波形；生产模块没有时钟或复位。
private class SubwordReadHarness(dataWidth: Int) extends Module {
  val io = IO(new SubwordReadIO(dataWidth))
  val dut = Module(new SubwordRead(dataWidth))
  dut.io.ReadDataWord := io.ReadDataWord
  dut.io.ByteOffset := io.ByteOffset
  dut.io.Funct3 := io.Funct3
  io.ReadData := dut.io.ReadData
}

class SubwordReadSpec extends AnyFlatSpec with ChiselScalatestTester {
  // 设置输入后立即检查组合输出；只有波形测试按需推进观察时钟。
  private def check(
    dut: SubwordReadHarness,
    data: BigInt,
    offset: Int,
    funct3: Int,
    expected: BigInt
  ): Unit = {
    dut.io.ReadDataWord.poke(data.U)
    dut.io.ByteOffset.poke(offset.U)
    dut.io.Funct3.poke(funct3.U)
    dut.io.ReadData.expect(expected.U)
  }

  private def hex(value: String): BigInt = BigInt(value, 16)

  private def wordMask(width: Int): BigInt = (BigInt(1) << width) - 1

  // 软件按小端字节数组取值，负数用减去 2^位数计算，不复用硬件选择器。
  private def reference(width: Int, data: BigInt, offset: Int, funct3: Int): BigInt = {
    val loads = Map(MemoryFunction.BYTE -> (1, true), MemoryFunction.HALF -> (2, true),
      MemoryFunction.WORD -> (4, true),
      MemoryFunction.BYTE_UNSIGNED -> (1, false), MemoryFunction.HALF_UNSIGNED -> (2,
        false)) ++
      (if (width == 64) Map(MemoryFunction.DOUBLE -> (8, true),
        MemoryFunction.WORD_UNSIGNED -> (4, false)) else Map.empty)

    loads.get(funct3).map { case (size, signed) =>
      val bytes = (0 until width / 8).map(lane => (data >> (8 * lane)) & Mask.BYTE)
      val firstByte = offset / size * size // 对应忽略大小相关的低偏移位。
      val unsigned = bytes.slice(firstByte, firstByte + size).reverse
        .foldLeft(BigInt(0))((value, byte) => (value << 8) | byte)
      val modulus = BigInt(1) << (size * 8)
      val value = if (signed && unsigned >= modulus / 2) unsigned - modulus else unsigned
      value & wordMask(width)
    }.getOrElse(BigInt(0))
  }

  behavior of "SubwordRead"

  for (width <- Seq(32, 64)) {
    it should s"demonstrate fixed loads and generate a waveform at $width bits" in {
      test(new SubwordReadHarness(width)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
        // 小端字节依次为 01、7F、FF、80；64 位高字为 80000001。
        val data = hex(if (width == 32) "80ff7f01" else "8000000180ff7f01")
        // 列顺序：Funct3、字节偏移、手工计算的期望值。
        val cases = if (width == 32) Seq(
          (MemoryFunction.BYTE, 0, "00000001"), (MemoryFunction.BYTE, 1, "0000007f"), // LB，正数。
          (MemoryFunction.BYTE, 2, "ffffffff"), (MemoryFunction.BYTE, 3, "ffffff80"), // LB，负数。
          (MemoryFunction.BYTE_UNSIGNED, 2, "000000ff"), (MemoryFunction.BYTE_UNSIGNED, 3,
            "00000080"), // LBU。
          (MemoryFunction.HALF, 0, "00007f01"), (MemoryFunction.HALF, 2, "ffff80ff"), // LH。
          (MemoryFunction.HALF_UNSIGNED, 2, "000080ff"),                     // LHU。
          (MemoryFunction.WORD, 0, "80ff7f01")                      // LW。
        ) else Seq(
          (MemoryFunction.BYTE, 0, "0000000000000001"), (MemoryFunction.BYTE, 1,
            "000000000000007f"),
          (MemoryFunction.BYTE, 2, "ffffffffffffffff"), (MemoryFunction.BYTE, 3,
            "ffffffffffffff80"),
          (MemoryFunction.BYTE_UNSIGNED, 2, "00000000000000ff"),
            (MemoryFunction.BYTE_UNSIGNED, 3, "0000000000000080"),
          (MemoryFunction.HALF, 0, "0000000000007f01"), (MemoryFunction.HALF, 2,
            "ffffffffffff80ff"),
          (MemoryFunction.HALF_UNSIGNED, 2, "00000000000080ff"),
          (MemoryFunction.WORD, 0, "ffffffff80ff7f01"), // LW，选低字并符号扩展。
          (MemoryFunction.BYTE, 7, "ffffffffffffff80"), // LB，选最高字节。
          (MemoryFunction.HALF, 4, "0000000000000001"), (MemoryFunction.HALF, 6,
            "ffffffffffff8000"),
          (MemoryFunction.WORD, 4, "ffffffff80000001"), // LW，选高字并符号扩展。
          (MemoryFunction.WORD_UNSIGNED, 0, "0000000080ff7f01"),
            (MemoryFunction.WORD_UNSIGNED, 4, "0000000080000001"), // LWU。
          (MemoryFunction.DOUBLE, 0, "8000000180ff7f01")  // LD，原样输出。
        )

        dut.reset.poke(false.B)
        check(dut, BigInt(0), 0, MemoryFunction.BYTE, BigInt(0))
        dut.clock.step(2)
        for ((funct3, offset, expectedHex) <- cases) {
          check(dut, data, offset, funct3, hex(expectedHex))
          dut.clock.step(2)
        }
        check(dut, data, 0, 7, BigInt(0)) // 非法编码清零。
        dut.clock.step(2)
        check(dut, data, 0, MemoryFunction.BYTE, BigInt(1)) // 恢复合法读取。
        dut.clock.step(2)
      }
    }

    it should s"extend zero and both sides of every sign boundary at $width bits" in {
      test(new SubwordReadHarness(width)) { dut =>
        // LB/LH/LW：零、最大正数、最小负数、全一。
        val cases = if (width == 32) Seq(
          (MemoryFunction.BYTE, "00", "00000000"), (MemoryFunction.BYTE, "7f", "0000007f"),
          (MemoryFunction.BYTE, "80", "ffffff80"), (MemoryFunction.BYTE, "ff", "ffffffff"),
          (MemoryFunction.HALF, "0000", "00000000"), (MemoryFunction.HALF, "7fff", "00007fff"),
          (MemoryFunction.HALF, "8000", "ffff8000"), (MemoryFunction.HALF, "ffff", "ffffffff"),
          (MemoryFunction.WORD, "00000000", "00000000"), (MemoryFunction.WORD, "7fffffff",
            "7fffffff"),
          (MemoryFunction.WORD, "80000000", "80000000"), (MemoryFunction.WORD, "ffffffff",
            "ffffffff")
        ) else Seq(
          (MemoryFunction.BYTE, "00", "0000000000000000"), (MemoryFunction.BYTE, "7f",
            "000000000000007f"),
          (MemoryFunction.BYTE, "80", "ffffffffffffff80"), (MemoryFunction.BYTE, "ff",
            "ffffffffffffffff"),
          (MemoryFunction.HALF, "0000", "0000000000000000"), (MemoryFunction.HALF, "7fff",
            "0000000000007fff"),
          (MemoryFunction.HALF, "8000", "ffffffffffff8000"), (MemoryFunction.HALF, "ffff",
            "ffffffffffffffff"),
          (MemoryFunction.WORD, "00000000", "0000000000000000"), (MemoryFunction.WORD,
            "7fffffff", "000000007fffffff"),
          (MemoryFunction.WORD, "80000000", "ffffffff80000000"), (MemoryFunction.WORD,
            "ffffffff", "ffffffffffffffff")
        )
        for ((funct3, dataHex, signedHex) <- cases) {
          val data = hex(dataHex)
          check(dut, data, 0, funct3, hex(signedHex))
          // 对应 LBU/LHU/LWU 应保留正值；RV32 不支持 LWU。
          if (funct3 < 2 || width == 64) check(dut, data, 0, funct3 + 4, data)
        }
      }
    }

    it should s"reject every illegal load code at every offset at $width bits" in {
      test(new SubwordReadHarness(width)) { dut =>
        val illegalCodes = if (width == 32) Seq(3, 6, 7) else Seq(7)
        for (funct3 <- illegalCodes; offset <- 0 until width / 8) {
          check(dut, wordMask(width), offset, MemoryFunction.BYTE, wordMask(width))
          check(dut, wordMask(width), offset, funct3, BigInt(0))
        }
      }
    }

    it should s"use only the relevant offset bits for wider loads at $width bits" in {
      test(new SubwordReadHarness(width)) { dut =>
        val data = hex(if (width == 32) "80ff7f01" else "8000000180ff7f01")
        for (offset <- 0 until width / 8) {
          check(dut, data, offset, MemoryFunction.HALF, reference(width, data, offset, 1))
          check(dut, data, offset, MemoryFunction.WORD, reference(width, data, offset, 2))
          if (width == 64) check(dut, data, offset, MemoryFunction.DOUBLE, data)
        }
        // 本模块只选子字；未自然对齐的读零门控由后续 LSU 实施。
      }
    }

    it should s"isolate every selected subword from neighboring bytes at $width bits" in {
      test(new SubwordReadHarness(width)) { dut =>
        val loadSizes = Seq(MemoryFunction.BYTE -> 1, MemoryFunction.BYTE_UNSIGNED -> 1,
          MemoryFunction.HALF -> 2, MemoryFunction.HALF_UNSIGNED -> 2) ++
          (if (width == 64) Seq(MemoryFunction.WORD -> 4,
            MemoryFunction.WORD_UNSIGNED -> 4) else Seq.empty)
        for ((funct3, size) <- loadSizes; offset <- 0 until width / 8 by size) {
          val selectedMask = ((BigInt(1) << (8 * size)) - 1) << (8 * offset)
          val selectedValue = ((BigInt(1) << (8 * size - 1)) | 1) << (8 * offset)
          val neighborsOne = selectedValue | (wordMask(width) ^ selectedMask)
          val expected = reference(width, selectedValue, offset, funct3)
          check(dut, selectedValue, offset, funct3, expected)
          check(dut, neighborsOne, offset, funct3, expected)
        }
      }
    }

    it should s"update word offset and function without a clock edge at $width bits" in {
      test(new SubwordReadHarness(width)) { dut =>
        val data = hex("80ff7f01")
        val negativeByte = hex(if (width == 32) "ffffff80" else "ffffffffffffff80")
        check(dut, data, 0, MemoryFunction.BYTE, BigInt(1))
        check(dut, data, 3, MemoryFunction.BYTE, negativeByte) // 仅偏移变化。
        check(dut, data, 3, MemoryFunction.BYTE_UNSIGNED, BigInt(128)) // 仅功能变化：符号扩展 → 零扩展。
        check(dut, data, 3, 7, BigInt(0))
        check(dut, data, 3, MemoryFunction.BYTE, negativeByte) // 恢复合法读取。
        check(dut, BigInt(1), 3, MemoryFunction.BYTE, BigInt(0)) // 仅数据变化。
        check(dut, BigInt(1), 0, MemoryFunction.BYTE, BigInt(1))
        check(dut, BigInt(1), 0, MemoryFunction.WORD, BigInt(1))
      }
    }

    it should s"match a seeded byte reference for all codes and offsets at $width bits" in {
      test(new SubwordReadHarness(width)) { dut =>
        val random = new Random(0x5eed11L + width)
        val edges = Seq(BigInt(0), wordMask(width), BigInt(1) << (width - 1))
        val inputs = edges ++ Seq.fill(128)(BigInt(width, random))
        for (data <- inputs; funct3 <- 0 until 8; offset <- 0 until width / 8) {
          check(dut, data, offset, funct3, reference(width, data, offset, funct3))
        }
      }
    }
  }

  it should "emit only four combinational ports at 32 and 64 bits" in {
    for (width <- Seq(32, 64)) {
      val targetDir = Files.createTempDirectory("subwordread-interface-")
      val verilog = (new ChiselStage).emitVerilog(new SubwordRead(width),
        Array("--target-dir", targetDir.toString))
      val pattern = "(?m)^\\s*(input|output)\\s+(?:\\[(\\d+):0\\]\\s+)?(\\w+)".r
      val ports = pattern.findAllMatchIn(verilog).map { port =>
        val portWidth = Option(port.group(2)).map(_.toInt + 1).getOrElse(1)
        port.group(3) -> (port.group(1), portWidth)
      }.toMap
      assert(ports == Map(
        "io_ReadDataWord" -> ("input", width),
        "io_ByteOffset" -> ("input", chisel3.util.log2Ceil(width / 8)),
        "io_Funct3" -> ("input", 3),
        "io_ReadData" -> ("output", width)
      ))
      assert(!verilog.contains("always @"))
      assert(!verilog.contains("reg "))
    }
  }

  it should "reject unsupported data widths during elaboration" in {
    for (width <- Seq(-1, 0, 1, 8, 16, 31, 33, 63, 65, 128)) {
      val error = intercept[IllegalArgumentException] {
        (new ChiselStage).emitChirrtl(new SubwordRead(width))
      }
      assert(error.getMessage.contains("SubwordRead dataWidth must be 32 or 64"))
    }
  }
}
