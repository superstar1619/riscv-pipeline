package riscvpipeline.ebu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

private class AHBInterfaceHarness(readOnly: Boolean) extends Module {
  val io = IO(new AHBInterfaceIO)
  val dut = Module(new AHBInterface(readOnly))
  dut.clk := clock
  dut.reset := reset.asBool
  io <> dut.io
}

class AHBInterfaceSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  private def init(d: AHBInterfaceHarness): Unit = {
    d.io.BusRW.poke(0.U)
    d.io.Stall.poke(false.B)
    d.io.Flush.poke(false.B)
    d.io.HREADY.poke(true.B)
    d.io.HRDATA.poke(0.U)
    d.io.WriteData.poke(0.U)
    d.io.ByteMask.poke(0.U)
    d.reset.poke(true.B)
    d.clock.step()
    d.reset.poke(false.B)
  }

  behavior of "AHBInterface"
  for (readOnly <- Seq(false, true)) {
    it should s"capture only completed reads and retain them through Stall/Flush ($readOnly)" in {
      test(new AHBInterfaceHarness(readOnly)) { d =>
        init(d)
        d.io.BusRW.poke(2.U)
        d.io.HRDATA.poke("h11111111".U)
        d.io.Stall.poke(true.B)
        d.clock.step() // accepted address
        d.io.BusRW.poke(0.U)
        d.io.HREADY.poke(false.B)
        d.io.Flush.poke(true.B)
        d.clock.step(3)
        d.io.FetchBuffer.expect(0.U)
        d.io.HRDATA.poke("h87654321".U)
        d.io.HREADY.poke(true.B)
        d.clock.step() // completion, despite Stall/Flush
        d.io.FetchBuffer.expect("h87654321".U)
        d.io.HRDATA.poke("hffffffff".U)
        d.clock.step(4)
        d.io.FetchBuffer.expect("h87654321".U)
        d.io.HTRANS.expect(0.U)
        d.io.BusCommitted.expect((!readOnly).B)
      }
    }
  }

  it should "latch only accepted store data and byte lanes and hold through waits" in {
    test(new AHBInterfaceHarness(false)) { d =>
      init(d)
      d.io.BusRW.poke(1.U)
      d.io.HREADY.poke(false.B)
      d.io.WriteData.poke("h12345678".U)
      d.io.ByteMask.poke(5.U)
      d.clock.step(2)
      d.io.HWDATA.expect(0.U)
      d.io.HWSTRB.expect(0.U)
      d.io.HREADY.poke(true.B)
      d.clock.step()
      d.io.HREADY.poke(false.B)
      d.io.WriteData.poke("hdeadbeef".U)
      d.io.ByteMask.poke(10.U)
      d.clock.step(3)
      d.io.HWDATA.expect("h12345678".U)
      d.io.HWSTRB.expect(5.U)
      d.io.HREADY.poke(true.B)
      d.io.HRDATA.poke("hffffffff".U)
      d.clock.step()
      d.io.FetchBuffer.expect(0.U) // writes never overwrite a read response
    }
  }

  it should "suppress stores and fix write outputs to zero in read-only mode" in {
    test(new AHBInterfaceHarness(true)) { d =>
      init(d)
      d.io.BusRW.poke(1.U)
      d.io.WriteData.poke("hffffffff".U)
      d.io.ByteMask.poke(15.U)
      d.clock.step(3)
      d.io.HTRANS.expect(0.U)
      d.io.HWRITE.expect(false.B)
      d.io.HWDATA.expect(0.U)
      d.io.HWSTRB.expect(0.U)
      d.io.BusStall.expect(false.B)
    }
  }

  it should "cancel only unaccepted flushed writes without updating payload buffers" in {
    test(new AHBInterfaceHarness(false)) { d =>
      init(d)
      d.io.BusRW.poke(1.U)
      d.io.Flush.poke(true.B)
      d.io.WriteData.poke("h12345678".U)
      d.io.ByteMask.poke(15.U)
      d.clock.step(2)
      d.io.HTRANS.expect(0.U)
      d.io.HWDATA.expect(0.U)
      d.io.HWSTRB.expect(0.U)
    }
  }
}
