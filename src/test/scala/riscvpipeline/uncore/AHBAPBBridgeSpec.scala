package riscvpipeline.uncore

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

private class BridgeHarness extends Module {
  val io = IO(new AHBAPBBridgeIO)
  val dut = Module(new AHBAPBBridge)
  dut.clk := clock
  dut.reset := reset.asBool
  io <> dut.io
}
class AHBAPBBridgeSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  private def boot(d: BridgeHarness): Unit = {
    d.io.ahb.HSEL.poke(false.B); d.io.ahb.HADDR.poke(0.U)
    d.io.ahb.HWDATA.poke(0.U); d.io.ahb.HWSTRB.poke(0.U)
    d.io.ahb.HWRITE.poke(false.B); d.io.ahb.HSIZE.poke(2.U)
    d.io.ahb.HTRANS.poke(0.U); d.io.ahb.HREADY.poke(true.B)
    d.io.apb.PREADY.poke(false.B); d.io.apb.PRDATA.poke(0.U)
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    d.io.apb.PSEL.expect(false.B); d.io.ahb.HREADYOUT.expect(true.B)
  }
  behavior of "AHBAPBBridge"
  it should "produce Setup then Access and freeze all fields until PREADY" in {
    test(new BridgeHarness) { d =>
      boot(d)
      d.io.ahb.HSEL.poke(true.B); d.io.ahb.HTRANS.poke(2.U)
      d.io.ahb.HADDR.poke("h10000004".U); d.io.ahb.HWRITE.poke(true.B)
      d.io.ahb.HWDATA.poke("hbad00000".U); d.clock.step()
      d.io.ahb.HSEL.poke(false.B); d.io.ahb.HTRANS.poke(0.U)
      d.io.ahb.HREADY.poke(false.B)
      d.io.ahb.HWDATA.poke("h12345678".U); d.io.ahb.HWSTRB.poke(5.U)
      d.io.apb.PSEL.expect(true.B); d.io.apb.PENABLE.expect(false.B)
      d.io.apb.PWDATA.expect("h12345678".U); d.io.ahb.HREADYOUT.expect(false.B)
      d.clock.step()
      for (_ <- 0 until 4) {
        d.io.ahb.HADDR.poke(0.U); d.io.ahb.HWDATA.poke(0.U)
        d.io.ahb.HWSTRB.poke(0.U); d.io.ahb.HWRITE.poke(false.B)
        d.io.apb.PSEL.expect(true.B); d.io.apb.PENABLE.expect(true.B)
        d.io.apb.PADDR.expect("h10000004".U); d.io.apb.PWRITE.expect(true.B)
        d.io.apb.PWDATA.expect("h12345678".U); d.io.apb.PSTRB.expect(5.U)
        d.io.ahb.HREADYOUT.expect(false.B); d.clock.step()
      }
      d.io.apb.PREADY.poke(true.B); d.io.apb.PRDATA.poke("hcafebabe".U)
      d.io.ahb.HREADY.poke(true.B); d.io.ahb.HREADYOUT.expect(true.B)
      d.io.ahb.HRDATA.expect("hcafebabe".U); d.clock.step()
      d.io.apb.PSEL.expect(false.B); d.io.apb.PENABLE.expect(false.B)
      d.io.apb.PRDATA.poke(0.U); d.io.ahb.HRDATA.expect("hcafebabe".U)
    }
  }
  it should "accept the next address at completion without skipping its Setup phase" in {
    test(new BridgeHarness) { d =>
      boot(d)
      d.io.ahb.HSEL.poke(true.B); d.io.ahb.HTRANS.poke(2.U)
      d.io.ahb.HADDR.poke(16.U); d.clock.step()
      d.io.ahb.HREADY.poke(false.B); d.clock.step()
      d.io.apb.PENABLE.expect(true.B)
      d.io.apb.PREADY.poke(true.B); d.io.apb.PRDATA.poke(55.U)
      d.io.ahb.HREADY.poke(true.B); d.io.ahb.HADDR.poke(32.U)
      d.io.ahb.HRDATA.expect(55.U); d.clock.step()
      d.io.apb.PADDR.expect(32.U); d.io.apb.PSEL.expect(true.B)
      d.io.apb.PENABLE.expect(false.B); d.io.ahb.HREADYOUT.expect(false.B)
      d.io.ahb.HSEL.poke(false.B); d.io.ahb.HTRANS.poke(0.U)
      d.io.ahb.HREADY.poke(false.B); d.clock.step()
      d.io.apb.PENABLE.expect(true.B); d.io.apb.PRDATA.poke(66.U)
      d.io.ahb.HREADYOUT.expect(true.B); d.io.ahb.HRDATA.expect(66.U)
      d.io.ahb.HREADY.poke(true.B); d.clock.step()
      d.io.apb.PSEL.expect(false.B)
    }
  }
  it should "ignore unaccepted requests and synchronously cancel a pending APB transfer" in {
    test(new BridgeHarness) { d =>
      boot(d)
      d.io.ahb.HSEL.poke(true.B); d.io.ahb.HTRANS.poke(2.U)
      d.io.ahb.HREADY.poke(false.B); d.clock.step(3)
      d.io.apb.PSEL.expect(false.B)
      d.io.ahb.HREADY.poke(true.B); d.clock.step()
      d.io.apb.PSEL.expect(true.B)
      d.reset.poke(true.B); d.io.apb.PSEL.expect(true.B)
      d.clock.step(); d.io.apb.PSEL.expect(false.B)
      d.io.ahb.HRESP.expect(false.B)
    }
  }
}
