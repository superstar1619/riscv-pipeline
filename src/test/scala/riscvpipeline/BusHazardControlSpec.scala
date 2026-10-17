package riscvpipeline

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import riscvpipeline.hazard.{BusHazardControl, BusHazardControlIO}

private class BusHazardHarness extends Module {
  val io = IO(new BusHazardControlIO)
  val dut = Module(new BusHazardControl)
  io <> dut.io
}

class BusHazardControlSpec extends AnyFlatSpec with ProjectTest {
  private def drive(d: BusHazardHarness, f: Boolean = false, m: Boolean = false,
    cf: Boolean = false, cm: Boolean = false, trap: Boolean = false,
    redirect: Boolean = false, wfi: Boolean = false): Unit = {
    d.io.IFUStallF.poke(f.B); d.io.LSUStallM.poke(m.B)
    d.io.CommittedF.poke(cf.B); d.io.CommittedM.poke(cm.B)
    d.io.TrapM.poke(trap.B); d.io.RedirectIntentM.poke(redirect.B)
    d.io.WFIStallM.poke(wfi.B)
  }

  "BusHazardControl" should "hold independently for fetch data and WFI waits" in {
    test(new BusHazardHarness) { d =>
      drive(d); d.io.StallM.expect(false.B)
      drive(d, f = true); d.io.StallM.expect(true.B)
      drive(d, m = true); d.io.StallM.expect(true.B)
      drive(d, wfi = true); d.io.StallM.expect(true.B)
      drive(d, f = true, m = true, cf = true, cm = true)
      d.io.StallM.expect(true.B)
      d.io.FlushBusF.expect(false.B); d.io.FlushBusM.expect(false.B)
    }
  }

  it should "cancel unaccepted requests while preserving each committed owner" in {
    test(new BusHazardHarness) { d =>
      drive(d, f = true, m = true, trap = true, redirect = true)
      d.io.FlushBusF.expect(true.B); d.io.FlushBusM.expect(true.B)
      d.io.StallM.expect(false.B)
      drive(d, f = true, m = true, cf = true, trap = true, redirect = true)
      d.io.FlushBusF.expect(false.B); d.io.FlushBusM.expect(true.B)
      d.io.StallM.expect(true.B)
      drive(d, f = true, m = true, cm = true, trap = true, redirect = true)
      d.io.FlushBusF.expect(true.B); d.io.FlushBusM.expect(false.B)
      d.io.StallM.expect(true.B)
      drive(d, f = true, m = true, cf = true, cm = true, trap = true, redirect = true)
      d.io.FlushBusF.expect(false.B); d.io.FlushBusM.expect(false.B)
      d.io.StallM.expect(true.B)
    }
  }

  it should "cancel only fetch for CSR fence or return intents" in {
    test(new BusHazardHarness) { d =>
      drive(d, f = true, redirect = true)
      d.io.FlushBusF.expect(true.B); d.io.FlushBusM.expect(false.B)
      d.io.StallM.expect(false.B)
      drive(d, f = true, m = true, redirect = true)
      d.io.FlushBusF.expect(true.B); d.io.FlushBusM.expect(false.B)
      d.io.StallM.expect(true.B)
      drive(d, f = true, cf = true, redirect = true)
      d.io.FlushBusF.expect(false.B); d.io.StallM.expect(true.B)
    }
  }
}
