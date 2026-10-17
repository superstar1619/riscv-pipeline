package riscvpipeline.uncore

import chisel3._
import chiseltest._
import chiseltest.experimental.expose
import org.scalatest.flatspec.AnyFlatSpec
import riscvpipeline.ebu.AHBSlaveIO

private class RAMAHBHarness(latency: Int) extends Module {
  val io = IO(new AHBSlaveIO)
  val dut = Module(new RAMAHB(sizeBytes = 64, extraLatency = latency))
  dut.clk := clock
  dut.reset := reset.asBool
  io <> dut.io
  val WriteEnable = expose(dut.WriteEnable)
}

class RAMAHBSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  private def idle(d: RAMAHBHarness): Unit = {
    d.io.HSEL.poke(false.B); d.io.HTRANS.poke(0.U); d.io.HREADY.poke(true.B)
    d.io.HADDR.poke("h80000000".U); d.io.HSIZE.poke(2.U)
    d.io.HWRITE.poke(false.B); d.io.HWDATA.poke(0.U); d.io.HWSTRB.poke(0.U)
  }
  private def address(d: RAMAHBHarness, offset: Int, write: Boolean): Unit = {
    d.io.HSEL.poke(true.B); d.io.HTRANS.poke(2.U); d.io.HREADY.poke(true.B)
    d.io.HADDR.poke((BigInt("80000000", 16) + offset).U)
    d.io.HWRITE.poke(write.B); d.clock.step()
    d.io.HSEL.poke(false.B); d.io.HTRANS.poke(0.U)
  }
  private def complete(d: RAMAHBHarness, latency: Int): Unit = {
    for (_ <- 0 until latency) {
      d.io.HREADYOUT.expect(false.B); d.io.HREADY.poke(false.B); d.clock.step()
    }
    d.io.HREADYOUT.expect(true.B); d.io.HREADY.poke(true.B)
  }
  behavior of "RAMAHB"
  for (latency <- 0 to 2) {
    it should s"retain synchronous read data and apply byte strobes with $latency extra waits" in {
      test(new RAMAHBHarness(latency)) { d =>
        idle(d); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        address(d, 0, true)
        d.io.HWDATA.poke("h11223344".U); d.io.HWSTRB.poke(15.U)
        complete(d, latency); d.clock.step()
        address(d, 0, true)
        d.io.HWDATA.poke("haabbccdd".U); d.io.HWSTRB.poke(5.U)
        complete(d, latency); d.clock.step()
        address(d, 0, false)
        complete(d, latency); d.io.HRDATA.expect("h11bb33dd".U)
        d.clock.step(4); d.io.HRDATA.expect("h11bb33dd".U)
        d.io.HRESP.expect(false.B)
      }
    }
  }
  it should "insert a structural wait for a read accepted at the preceding write completion" in {
    test(new RAMAHBHarness(0)) { d =>
      idle(d); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      address(d, 0, true)
      d.io.HWDATA.poke("h12345678".U); d.io.HWSTRB.poke(15.U)
      address(d, 0, false)
      d.io.HREADYOUT.expect(false.B); d.io.HREADY.poke(false.B)
      d.clock.step(); d.io.HREADYOUT.expect(true.B)
      d.io.HRDATA.expect("h12345678".U)
    }
  }
  it should "perform exactly one physical write pulse at the completion edge" in {
    test(new RAMAHBHarness(2)) { d =>
      idle(d); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      address(d, 0, true)
      d.io.HWSTRB.poke(15.U)
      for (value <- Seq(11, 22)) {
        d.io.HWDATA.poke(value.U); d.io.HREADY.poke(false.B)
        d.WriteEnable.expect(false.B); d.clock.step()
      }
      d.io.HWDATA.poke(33.U); d.io.HREADY.poke(true.B)
      d.WriteEnable.expect(true.B); d.clock.step()
      for (_ <- 0 until 4) { d.WriteEnable.expect(false.B); d.clock.step() }
      address(d, 0, false); complete(d, 2); d.io.HRDATA.expect(33.U)
    }
  }
  it should "ignore unaccepted address phases and reset pending transfers synchronously" in {
    test(new RAMAHBHarness(2)) { d =>
      idle(d); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.HSEL.poke(true.B); d.io.HTRANS.poke(2.U); d.io.HREADY.poke(false.B)
      d.clock.step(3); d.io.HREADYOUT.expect(true.B)
      d.io.HREADY.poke(true.B); d.clock.step(); d.io.HREADYOUT.expect(false.B)
      d.reset.poke(true.B); d.io.HREADYOUT.expect(false.B)
      d.clock.step(); d.io.HREADYOUT.expect(true.B)
    }
  }
}
