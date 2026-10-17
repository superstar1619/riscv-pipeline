package riscvpipeline.ebu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

private class EBUFSMArbHarness extends Module {
  val io = IO(new EBUFSMArbIO)
  val dut = Module(new EBUFSMArb)
  dut.clk := clock
  dut.reset := reset.asBool
  io <> dut.io
}

class EBUFSMArbSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  private def init(d: EBUFSMArbHarness): Unit = {
    d.io.LSUReq.poke(false.B)
    d.io.IFUReq.poke(false.B)
    d.io.HREADY.poke(true.B)
    d.io.HBURST.poke(0.U)
    d.reset.poke(true.B)
    d.clock.step()
    d.reset.poke(false.B)
  }
  behavior of "EBUFSMArb"
  it should "select independent IFU and LSU requests without saving" in {
    test(new EBUFSMArbHarness) { d =>
      init(d)
      d.io.IFUReq.poke(true.B)
      d.io.IFUSelect.expect(true.B)
      d.io.LSUSelect.expect(false.B)
      d.io.IFUSave.expect(false.B)
      d.io.IFUReq.poke(false.B)
      d.io.LSUReq.poke(true.B)
      d.io.IFUSelect.expect(false.B)
      d.io.LSUSelect.expect(true.B)
    }
  }
  it should "grant LSU first, save once, wait and then restore IFU" in {
    test(new EBUFSMArbHarness) { d =>
      init(d)
      d.io.IFUReq.poke(true.B)
      d.io.LSUReq.poke(true.B)
      d.io.IFUSave.expect(true.B)
      d.io.LSUSelect.expect(true.B)
      d.io.IFUSelect.expect(false.B)
      d.clock.step()
      d.io.LSUReq.poke(false.B)
      d.io.HREADY.poke(false.B)
      d.io.IFUSave.expect(false.B)
      d.io.IFURestore.expect(true.B)
      d.io.IFUDisable.expect(true.B)
      d.io.LSUDisable.expect(false.B)
      d.clock.step(4)
      d.io.LSUSelect.expect(true.B)
      d.io.HREADY.poke(true.B)
      d.io.IFUSelect.expect(true.B)
      d.io.LSUSelect.expect(false.B)
      d.clock.step()
      d.io.IFURestore.expect(false.B)
      d.io.IFUDisable.expect(false.B)
      d.io.LSUDisable.expect(true.B)
      d.io.IFUReq.poke(false.B)
      d.clock.step()
      d.io.LSUDisable.expect(false.B)
    }
  }
  it should "hold arbitration while both requests remain asserted" in {
    test(new EBUFSMArbHarness) { d =>
      init(d)
      d.io.IFUReq.poke(true.B)
      d.io.LSUReq.poke(true.B)
      d.clock.step(3)
      d.io.IFURestore.expect(true.B)
      d.io.IFUSave.expect(false.B)
      d.io.LSUSelect.expect(true.B)
    }
  }
}
