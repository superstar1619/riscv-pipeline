package riscvpipeline.uncore

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import riscvpipeline.config.BusConfig

private class AdrDecsHarness(config: BusConfig) extends Module {
  val io = IO(new AdrDecsIO)
  val dut = Module(new AdrDecs(config))
  io <> dut.io
}
class AdrDecsSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  behavior of "AdrDecs"
  it should "select exactly one window or None for every boundary and size" in {
    val c = BusConfig(ramBase = BigInt(1) << 33)
    test(new AdrDecsHarness(c)) { d =>
      val windows = Seq((c.bootRomBase, c.bootRomBytes, 2), (c.ramBase, c.ramBytes, 4),
        (c.apbBase, c.apbBytes, 8))
      for ((base, bytes, sel) <- windows; off <- Seq(-1, 0, bytes - 1, bytes);
        size <- 0 until 8) {
        d.io.HADDR.poke((base + off).U)
        d.io.HSIZE.poke(size.U)
        d.io.HSEL.expect((if (off >= 0 && off < bytes && size <= 2) sel else 1).U)
      }
    }
  }
}
