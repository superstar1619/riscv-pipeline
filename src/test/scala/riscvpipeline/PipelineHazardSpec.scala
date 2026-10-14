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
}
