package riscvpipeline.uncore

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import riscvpipeline.config.BusConfig

private class AdrDecHarness extends Module {
  val io = IO(new AdrDecIO)
  val dut = Module(new AdrDec)
  io <> dut.io
}

class AdrDecSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  behavior of "AdrDec and BusConfig"
  it should "validate aligned nonoverlapping 34-bit windows and latency bounds" in {
    assert(BusConfig().ramBytes == 65536)
    for (bad <- Seq(() => BusConfig(ramBytes = 3), () => BusConfig(ramBase = 3),
      () => BusConfig(apbBase = 0x1000), () => BusConfig(ramLatency = 256),
      () => BusConfig(bootRomBase = BigInt(1) << 34))) {
      intercept[IllegalArgumentException](bad())
    }
    BusConfig(ramBase = BigInt(1) << 33, ramLatency = 255)
  }
  it should "check range boundaries, enables and independently masked access sizes" in {
    test(new AdrDecHarness) { d =>
      d.io.Base.poke("h200001000".U)
      d.io.Range.poke(4095.U)
      d.io.Supported.poke(true.B)
      d.io.AccessValid.poke(true.B)
      d.io.SizeMask.poke(5.U)
      for (a <- Seq(BigInt("200000fff", 16), BigInt("200001000", 16),
        BigInt("200001fff", 16), BigInt("200002000", 16)); s <- 0 until 4) {
        d.io.PhysicalAddress.poke(a.U)
        d.io.Size.poke(s.U)
        d.io.Sel.expect((a >= BigInt("200001000", 16) &&
          a <= BigInt("200001fff", 16) && (s == 0 || s == 2)).B)
      }
      d.io.PhysicalAddress.poke("h200001000".U)
      d.io.Size.poke(0.U)
      d.io.Supported.poke(false.B)
      d.io.Sel.expect(false.B)
      d.io.Supported.poke(true.B)
      d.io.AccessValid.poke(false.B)
      d.io.Sel.expect(false.B)
    }
  }
}
