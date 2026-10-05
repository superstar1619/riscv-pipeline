package riscvpipeline

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import riscvpipeline.config.CpuConfig

class PipelineSmokeSpec extends AnyFlatSpec with ChiselScalatestTester {
  import PipelineTestSupport._
  behavior of "RV32I five-stage pipeline"

  it should "fill F D E M before observing the first store" in {
    test(new PipelineHarness(image(Seq(addi(1, 0, 7), store(1, 96), jal(0, 0))))) { dut =>
      reset(dut)
      val writes = stores(dut, 16)
      assert(writes == Seq((4, BigInt(96), BigInt(7))))
    }
  }

  it should "produce exactly the two textbook stores within 80 cycles" in {
    val config = CpuConfig(instructionInitFile = Some("programs/riscvtest.memfile"))
    test(new PipelineHarness(config)) { dut =>
      reset(dut)
      assert(stores(dut, 80).map(w => (w._2, w._3)) ==
        Seq((BigInt(96), BigInt(7)), (BigInt(100), BigInt(25))))
    }
  }

  it should "resolve forwarded branches and squash both younger stores" in {
    val words = Seq(addi(1, 0, 7), addi(2, 0, 7), branch(1, 2, 12),
      store(1, 80), store(1, 84), branch(1, 0, 8), store(1, 96), jal(0, 0))
    test(new PipelineHarness(image(words))) { dut =>
      reset(dut)
      assert(stores(dut, 32).map(w => (w._2, w._3)) ==
        Seq((BigInt(96), BigInt(7))))
    }
  }

  it should "forward a loaded value into branch comparison after exactly one stall" in {
    val words = Seq(addi(1, 0, 7), store(1, 64), NOP, load(2, 0, 64),
      branch(1, 2, 12), store(1, 80), store(1, 84), store(2, 96), jal(0, 0))
    test(new PipelineHarness(image(words))) { dut =>
      reset(dut)
      var stalls = 0
      val writes = (0 until 36).flatMap { _ =>
        if (dut.LoadStallD.peek().litToBoolean) stalls += 1
        stores(dut, 1).map(w => (w._2, w._3))
      }
      assert(stalls == 1)
      assert(writes == Seq((BigInt(64), BigInt(7)), (BigInt(96), BigInt(7))))
    }
  }

  it should "link JAL and forwarded JALR using their own PCs and clear target bit zero" in {
    // jal at 0 links 4; jalr at 20 links 24 and clears address 33 to 32.
    val jalr = BigInt(0x000082e7) // jalr x5,0(x1)
    val words = Seq(jal(3, 12), store(0, 80), store(0, 84),
      store(3, 96), addi(1, 0, 33), jalr, store(0, 88), store(0, 92),
      store(5, 100), jal(0, 0))
    test(new PipelineHarness(image(words))) { dut =>
      reset(dut)
      assert(stores(dut, 36).map(w => (w._2, w._3)) ==
        Seq((BigInt(96), BigInt(4)), (BigInt(100), BigInt(24))))
    }
  }

  it should "flush pending M stores and W writes on reset and retain established state" in {
    val words = Seq(load(2, 0, 64), store(2, 96), addi(9, 0, 5),
      addi(1, 0, 9), store(1, 64), addi(9, 0, 33), store(9, 68), jal(0, 0))
    test(new PipelineHarness(image(words, resetVector = 0x100))) { dut =>
      reset(dut)
      dut.PCF.expect(0x100.U)
      var reachedPendingStore = false
      for (_ <- 0 until 24 if !reachedPendingStore) {
        if (dut.io.MemWrite.peek().litToBoolean && dut.io.IEUAdr.peek().litValue == 68) {
          reachedPendingStore = true
        } else dut.clock.step()
      }
      assert(reachedPendingStore, "pending store must be reached within 24 cycles")
      dut.Register9.expect(5.U) // x9=33 is in W, has not committed yet.
      val oldPC = dut.PCF.peek().litValue
      dut.reset.poke(true.B)
      dut.io.MemWrite.expect(false.B)
      dut.io.ByteMask.expect(0.U)
      dut.io.MemRW.expect(0.U)
      dut.PCF.expect(oldPC.U) // Synchronous state does not change without an edge.
      dut.reset.poke(false.B)
      dut.PCF.expect(oldPC.U)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.PCF.expect(0x100.U)
      for (valid <- Seq(dut.ValidD, dut.ValidE, dut.ValidM, dut.ValidW)) {
        valid.expect(false.B)
      }
      dut.Register9.expect(5.U)
      dut.reset.poke(false.B)
      // Restarted load sees RAM[64]=9 retained across reset. No stale store at 68.
      assert(stores(dut, 7).map(w => (w._2, w._3)) ==
        Seq((BigInt(96), BigInt(9))))
    }
  }
}
