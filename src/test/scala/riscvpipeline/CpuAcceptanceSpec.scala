package riscvpipeline

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import riscvpipeline.config.CpuConfig
import scala.io.Source

/** 独立架构 trace 按访存顺序匹配，不将单周期行号作为流水线周期。 */
class CpuAcceptanceSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  behavior of "Pipeline RV32I acceptance"

  for ((suffix, vector, imem, dmem) <- Seq(("", 0, 128, 64),
    ("-expanded", 256, 256, 128))) {
    it should s"match all memory events and fixed signatures at vector $vector" in {
      val name = s"target/acceptance-images/rv32-acceptance$suffix"
      val source = Source.fromFile(s"$name.trace")
      val events = try {
        source.getLines().filterNot(_.startsWith("#"))
          .map(_.split("\\s+").map(BigInt(_, 16))).filter(_(4) != 0).toVector
      } finally source.close()
      val config = CpuConfig(imemDepth = imem, dmemDepth = dmem, resetVector = vector,
        instructionInitFile = Some(s"$name.memfile"))
      val expectedWords = Seq[Long](0, 0xfffffffeL, 0x80000000L, 0x80000001L,
        0x7fffffffL, 6, 0x40000000L, 0xc0000000L, 1, 0, 0xfffffff8L, 5,
        0x80000001L, 0x7fffffffL, 0x30, 0x08000000L, 0xf8000000L, 0, 1,
        0xfffff000L, vector + 0x10c4, 6, vector + 0x134, vector + 0x14c,
        0xffffffffL, 0xff, 0xffff8001L, 0x8001, 0x800100ffL, 0, 0x01000000)
      test(new PipelineHarness(config)) { dut =>
        // A second complete pass checks restart and established RAM retention.
        for (pass <- 0 until 2) {
          PipelineTestSupport.reset(dut)
          val ram = Array.fill[Option[Int]](dmem * 4)(None)
          var index = 0
          for (cycle <- 0 until 300) {
            val request = dut.io.MemRW.peek().litValue
            if (request != 0) {
              assert(index < events.length, s"extra memory event pass=$pass cycle=$cycle")
              val row = events(index)
              withClue(s"pass=$pass cycle=$cycle event=$index pc=${row(0).toString(16)}: ") {
                dut.io.IEUAdr.expect(row(2).U)
                dut.io.MemRW.expect(row(4).U)
                dut.io.Funct3.expect(row(5).U)
                dut.io.ByteMask.expect(row(6).U)
                dut.io.MemWrite.expect((row(6) != 0).B)
                if (row(6) != 0) {
                  dut.io.WriteData.expect(row(7).U)
                  val size = 1 << row(5).toInt
                  val base = (row(2).toInt & (ram.length - 1)) & ~3
                  for (lane <- 0 until 4 if row(6).testBit(lane)) {
                    ram(base + lane) = Some(((row(7) >> (8 * (lane % size))) & 255).toInt)
                  }
                }
              }
              index += 1
            }
            dut.clock.step()
          }
          assert(index == events.length, s"missing event $index/${events.length}")
          val signatures = expectedWords.zipWithIndex.map { case (value, i) =>
            (128 + 4 * i, BigInt(value))
          } ++ Seq(0 -> BigInt("800100ff", 16), 252 -> BigInt("01000000", 16))
          for ((address, expected) <- signatures) {
            val bytes = ram.slice(address, address + 4)
            assert(bytes.forall(_.isDefined), s"unwritten signature at $address")
            val actual = bytes.zipWithIndex.map { case (b, lane) =>
              BigInt(b.get) << (8 * lane)
            }.reduce(_ | _)
            assert(actual == expected, s"signature $address: $actual != $expected")
          }
        }
      }
    }
  }
}
