package riscvpipeline.uncore

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import riscvpipeline.ebu.AHBSlaveIO
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets

private class ROMAHBHarness(file: String, latency: Int) extends Module {
  val io = IO(new AHBSlaveIO)
  val dut = Module(new ROMAHB(sizeBytes = 16, initFile = Some(file), extraLatency = latency))
  dut.clk := clock
  dut.reset := reset.asBool
  io <> dut.io
}
class ROMAHBSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  behavior of "ROMAHB"
  for (latency <- 0 to 2) {
    it should s"read hex32 words synchronously and retain them through $latency waits" in {
      val image = Paths.get(s"target/chapter9/modules/rom-ahb/image-$latency.hex")
      Files.createDirectories(image.getParent)
      Files.write(image, "12345678\nabcdef01\n00000013\nffffffff\n"
        .getBytes(StandardCharsets.US_ASCII))
      test(new ROMAHBHarness(image.toString, latency)) { d =>
        d.io.HSEL.poke(false.B); d.io.HTRANS.poke(0.U); d.io.HREADY.poke(true.B)
        d.io.HWRITE.poke(false.B); d.io.HWDATA.poke(0.U); d.io.HWSTRB.poke(15.U)
        d.io.HSIZE.poke(2.U); d.io.HADDR.poke(4096.U)
        d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        for ((offset, expected) <- Seq((0, BigInt("12345678", 16)),
          (4, BigInt("abcdef01", 16)), (12, BigInt("ffffffff", 16)))) {
          d.io.HSEL.poke(true.B); d.io.HTRANS.poke(2.U)
          d.io.HADDR.poke((4096 + offset).U); d.clock.step()
          d.io.HSEL.poke(false.B); d.io.HTRANS.poke(0.U)
          d.io.HADDR.poke(4104.U)
          for (_ <- 0 until latency) {
            d.io.HREADYOUT.expect(false.B); d.io.HREADY.poke(false.B)
            d.io.HRDATA.expect(expected.U); d.clock.step()
          }
          d.io.HREADYOUT.expect(true.B); d.io.HRDATA.expect(expected.U)
          d.io.HREADY.poke(true.B); d.clock.step(3); d.io.HRDATA.expect(expected.U)
        }
        // ROM writes complete OKAY without changing storage or read response.
        d.io.HADDR.poke(4096.U); d.io.HSEL.poke(true.B); d.io.HTRANS.poke(2.U)
        d.io.HWRITE.poke(true.B); d.io.HWDATA.poke(0.U); d.clock.step()
        d.io.HSEL.poke(false.B); d.io.HTRANS.poke(0.U)
        for (_ <- 0 until latency) { d.io.HREADY.poke(false.B); d.clock.step() }
        d.io.HREADY.poke(true.B); d.clock.step()
        d.io.HWRITE.poke(false.B); d.io.HSEL.poke(true.B); d.io.HTRANS.poke(2.U)
        d.clock.step(); d.io.HRDATA.expect("h12345678".U)
        d.io.HRESP.expect(false.B)
      }
    }
  }
}
