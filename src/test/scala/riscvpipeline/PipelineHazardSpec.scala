package riscvpipeline

import riscvpipeline.TestEncoding.{Control}
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import riscvpipeline.hazard.{Hazard, HazardIO}

private class HazardHarness extends Module {
  val io = IO(new HazardIO)
  val dut = Module(new Hazard)
  dut.io <> io
}

class PipelineHazardSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  import PipelineTestSupport._
  private def clear(d: HazardHarness): Unit = {
    d.io.Rs1D.poke(0.U); d.io.Rs2D.poke(0.U)
    d.io.UsesRs1D.poke(false.B); d.io.UsesRs2D.poke(false.B)
    d.io.ValidD.poke(false.B); d.io.MemRWD.poke(0.U)
    d.io.Rs1E.poke(0.U); d.io.Rs2E.poke(0.U); d.io.RdE.poke(0.U)
    d.io.ValidE.poke(false.B); d.io.MemRWE.poke(0.U); d.io.LateResultE.poke(false.B)
    d.io.RdM.poke(0.U); d.io.RegWriteM.poke(false.B)
    d.io.ValidM.poke(false.B); d.io.ResultSrcM.poke(false.B)
    d.io.RdW.poke(0.U); d.io.RegWriteW.poke(false.B); d.io.ValidW.poke(false.B)
    d.io.PCSrcE.poke(false.B); d.io.RedirectM.poke(false.B)
    d.io.IFUStallF.poke(false.B); d.io.LSUStallM.poke(false.B)
    d.io.CommittedF.poke(false.B); d.io.CommittedM.poke(false.B)
    d.io.TrapM.poke(false.B); d.io.RedirectIntentM.poke(false.B); d.io.WFIStallM.poke(false.B)
  }

  behavior of "Pipeline dependencies"

  it should "stall once each for store-load and load-use while holding F and D" in {
    val words = Seq(addi(1, 0, 9), store(1, 64), load(2, 0, 64),
      addi(3, 2, 1), store(3, 96), jal(0, 0))
    test(new PipelineHarness(image(words))) { dut =>
      reset(dut)
      var loadStalls = 0
      var storeStalls = 0
      val writes = (0 until 24).flatMap { cycle =>
        val stalled = dut.StallD.peek().litToBoolean
        if (dut.LoadStallD.peek().litToBoolean) loadStalls += 1
        if (dut.StoreStallD.peek().litToBoolean) storeStalls += 1
        val before = Seq(dut.PCF, dut.PCD, dut.InstrD).map(_.peek().litValue)
        val write = if (dut.io.MemWrite.peek().litToBoolean) {
          Some((cycle, dut.io.IEUAdr.peek().litValue, dut.io.WriteData.peek().litValue))
        } else None
        dut.clock.step()
        if (stalled) {
          assert(Seq(dut.PCF, dut.PCD, dut.InstrD).map(_.peek().litValue) == before)
          dut.ValidE.expect(false.B)
        }
        write
      }
      assert(loadStalls == 1 && storeStalls == 1)
      assert(writes == Seq((4, BigInt(64), BigInt(9)),
        (9, BigInt(96), BigInt(10))))
    }
  }

  for ((name, words, expected) <- Seq(
    ("prefer M over W", Seq(addi(1, 0, 1), addi(1, 0, 2), addi(2, 1, 3)), 5),
    ("forward W to E", Seq(addi(1, 0, 11), NOP, addi(2, 1, 2)), 13),
    ("bypass W to D", Seq(addi(1, 0, 11), NOP, NOP, addi(2, 1, 2)), 13)
  )) {
    it should s"$name for register operands and forward the result to store data" in {
      test(new PipelineHarness(image(words ++ Seq(store(2, 96), jal(0, 0))))) { dut =>
        reset(dut)
        assert(stores(dut, 24).map(w => (w._2, w._3)) ==
          Seq((BigInt(96), BigInt(expected))))
      }
    }
  }

  it should "exclude x0 from forwarding and preserve architectural zero" in {
    val words = Seq(addi(0, 0, 99), addi(1, 0, 7), store(0, 92), store(1, 96), jal(0, 0))
    test(new PipelineHarness(image(words))) { dut =>
      reset(dut)
      assert(stores(dut, 24).map(w => (w._2, w._3)) ==
        Seq((BigInt(92), BigInt(0)), (BigInt(96), BigInt(7))))
    }
  }

  it should "ignore unused immediate and U-type source fields and loads into x0" in {
    val words = Seq(addi(2, 0, 9), store(2, 64), NOP,
      load(1, 0, 64), addi(3, 0, 1), load(1, 0, 64), BigInt(0x00008337),
      load(0, 0, 64), addi(4, 0, 1), store(3, 96), store(4, 100), jal(0, 0))
    test(new PipelineHarness(image(words))) { dut =>
      reset(dut)
      var stalls = 0
      val writes = (0 until 32).flatMap { _ =>
        if (dut.StallD.peek().litToBoolean) stalls += 1
        stores(dut, 1).map(w => (w._2, w._3))
      }
      assert(stalls == 0)
      assert(writes == Seq((BigInt(64), BigInt(9)),
        (BigInt(96), BigInt(1)), (BigInt(100), BigInt(1))))
    }
  }

  it should "gate forwarding by valid writes and let redirects override either stall cause" in {
    test(new HazardHarness) { dut =>
      clear(dut)
      dut.io.Rs1D.poke(5.U)
      dut.io.Rs2D.poke(5.U)
      dut.io.UsesRs1D.poke(true.B)
      dut.io.UsesRs2D.poke(true.B)
      dut.io.ValidD.poke(true.B)
      dut.io.MemRWD.poke(Control.MEM_READ.U)
      dut.io.Rs1E.poke(5.U)
      dut.io.Rs2E.poke(5.U)
      dut.io.RdE.poke(5.U)
      dut.io.ValidE.poke(true.B)
      dut.io.MemRWE.poke(Control.MEM_NONE.U)
      dut.io.RdM.poke(5.U)
      dut.io.RegWriteM.poke(true.B)
      dut.io.ValidM.poke(true.B)
      dut.io.ResultSrcM.poke(false.B)
      dut.io.RdW.poke(5.U)
      dut.io.RegWriteW.poke(true.B)
      dut.io.ValidW.poke(true.B)
      dut.io.PCSrcE.poke(false.B)
      dut.io.ForwardAE.expect(Control.FORWARD_M.U)
      dut.io.ForwardBE.expect(Control.FORWARD_M.U)
      dut.io.ValidM.poke(false.B)
      dut.io.ForwardAE.expect(Control.FORWARD_W.U)
      dut.io.ValidM.poke(true.B)
      dut.io.ResultSrcM.poke(true.B) // Load data is not an M→E ALU result.
      dut.io.ForwardAE.expect(Control.FORWARD_W.U)
      dut.io.ResultSrcM.poke(false.B)
      dut.io.RegWriteM.poke(false.B)
      dut.io.RegWriteW.poke(false.B)
      dut.io.ForwardAE.expect(Control.FORWARD_REGISTER.U)
      dut.io.RegWriteM.poke(true.B)
      dut.io.RegWriteW.poke(true.B)
      dut.io.RdM.poke(0.U)
      dut.io.RdW.poke(0.U)
      dut.io.Rs1E.poke(0.U)
      dut.io.ForwardAE.expect(Control.FORWARD_REGISTER.U)
      dut.io.ValidE.poke(false.B)
      dut.io.ForwardBE.expect(Control.FORWARD_REGISTER.U)
      dut.io.ValidE.poke(true.B)
      for (memRWE <- Seq(Control.MEM_READ, Control.MEM_WRITE)) {
        dut.io.MemRWE.poke(memRWE.U)
        dut.io.PCSrcE.poke(false.B)
        dut.io.StallF.expect(true.B)
        dut.io.StallD.expect(true.B)
        dut.io.FlushE.expect(true.B)
        dut.io.PCSrcE.poke(true.B)
        dut.io.StallF.expect(false.B)
        dut.io.StallD.expect(false.B)
        dut.io.FlushD.expect(true.B)
        dut.io.FlushE.expect(true.B)
      }
    }
  }
  private def drive(d: HazardHarness, f: Boolean = false, m: Boolean = false,
    cf: Boolean = false, cm: Boolean = false, trap: Boolean = false,
    redirect: Boolean = false, wfi: Boolean = false): Unit = {
    clear(d)
    d.io.IFUStallF.poke(f.B); d.io.LSUStallM.poke(m.B)
    d.io.CommittedF.poke(cf.B); d.io.CommittedM.poke(cm.B)
    d.io.TrapM.poke(trap.B); d.io.RedirectIntentM.poke(redirect.B)
    d.io.WFIStallM.poke(wfi.B)
  }

  it should "hold independently for fetch data and WFI waits" in {
    test(new HazardHarness) { d =>
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
    test(new HazardHarness) { d =>
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
    test(new HazardHarness) { d =>
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

  it should "combine dependency bubbles with waits and distinguish redirect intent from commit" in {
    // Hand-checked bus outcomes: f, m, committedF, committedM, trap, intent, wfi,
    // expected StallM, FlushBusF, FlushBusM. The same outcomes hold for every dependency.
    val cases = Seq(
      (false, false, false, false, false, false, false, false, false, false),
      (true, false, false, false, false, false, false, true, false, false),
      (false, true, false, false, false, false, false, true, false, false),
      (false, false, false, false, false, false, true, true, false, false),
      (true, false, false, false, false, true, false, false, true, false),
      (true, true, false, false, true, true, false, false, true, true),
      (true, true, true, false, true, true, false, true, false, true),
      (true, true, false, true, true, true, false, true, true, false),
      (true, true, true, true, true, true, false, true, false, false),
      (true, true, false, false, true, true, true, true, true, true),
      (true, true, false, false, false, true, false, true, true, false))
    test(new HazardHarness) { d =>
      for (dependency <- Seq("none", "load", "csr", "store");
        (f, m, cf, cm, trap, intent, wfi, stalled, cancelF, cancelM) <- cases;
        branch <- Seq(false, true); redirect <- Seq(false, true)) {
        drive(d, f, m, cf, cm, trap, intent, wfi)
        d.io.ValidD.poke(true.B); d.io.ValidE.poke(true.B)
        d.io.UsesRs1D.poke(true.B); d.io.Rs1D.poke(5.U); d.io.RdE.poke(5.U)
        d.io.MemRWD.poke(Control.MEM_READ.U)
        d.io.MemRWE.poke((dependency match {
          case "load" => Control.MEM_READ
          case "store" => Control.MEM_WRITE
          case _ => Control.MEM_NONE
        }).U)
        d.io.LateResultE.poke((dependency == "csr").B)
        d.io.PCSrcE.poke(branch.B); d.io.RedirectM.poke(redirect.B)
        d.io.LoadStallD.expect((dependency == "load" || dependency == "csr").B)
        d.io.StoreStallD.expect((dependency == "store").B)
        d.io.StallM.expect(stalled.B)
        d.io.StallF.expect((stalled || (dependency != "none" && !branch)).B)
        d.io.StallD.expect((stalled || (dependency != "none" && !branch)).B)
        d.io.FlushD.expect((branch || redirect).B)
        d.io.FlushE.expect((branch || dependency != "none").B)
        d.io.FlushBusF.expect(cancelF.B); d.io.FlushBusM.expect(cancelM.B)
      }
    }
  }
}
