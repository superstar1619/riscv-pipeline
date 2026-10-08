package riscvpipeline.privileged

import chisel3._
import chisel3.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.Files
import scala.util.Random

private class WFIHarness(timeoutCycles: Int) extends Module {
  val io = IO(new WFIIO(timeoutCycles))
  val dut = Module(new WFI(timeoutCycles))
  dut.clk := clock
  dut.reset := reset.asBool
  io <> dut.io
}

class WFISpec extends AnyFlatSpec with ChiselScalatestTester {
  private case class Inputs(valid: Boolean = true, wfi: Boolean = true,
    flush: Boolean = false, trap: Boolean = false, pending: Boolean = false)

  private def drive(d: WFIHarness, a: Inputs = Inputs()): Unit = {
    d.io.ValidM.poke(a.valid.B)
    d.io.wfiM.poke(a.wfi.B)
    d.io.FlushM.poke(a.flush.B)
    d.io.TrapM.poke(a.trap.B)
    d.io.IntPendingM.poke(a.pending.B)
  }

  private def boot(d: WFIHarness): Unit = {
    drive(d, Inputs(valid = false))
    d.reset.poke(true.B)
    d.clock.step()
    d.reset.poke(false.B)
    d.io.WaitCycles.expect(0.U)
    d.io.WFIStallM.expect(false.B)
    d.io.WFITimeoutM.expect(false.B)
  }

  behavior of "WFI"

  // 少计/多计一拍或用自身 stall 禁止计数都会破坏这些 deadline。
  for (deadline <- Seq(1, 3, 8)) {
    it should s"release exactly after $deadline waiting edges and saturate" in {
      test(new WFIHarness(deadline)) { d =>
        boot(d)
        drive(d)
        d.io.WaitCycles.expect(0.U)
        d.io.WFIStallM.expect(true.B)
        d.io.WFITimeoutM.expect(false.B)
        for (elapsed <- 1 to deadline) {
          d.clock.step()
          d.io.WaitCycles.expect(elapsed.U)
          d.io.WFIStallM.expect((elapsed < deadline).B)
          d.io.WFITimeoutM.expect((elapsed == deadline).B)
        }
        d.clock.step(deadline + 5)
        d.io.WaitCycles.expect(deadline.U)
        d.io.WFIStallM.expect(false.B)
        d.io.WFITimeoutM.expect(true.B)
      }
    }
  }

  it should "clear every cancellation independently and mask stale timeout immediately" in {
    val cancels = Seq(Inputs(valid = false), Inputs(wfi = false), Inputs(flush = true))
    test(new WFIHarness(3)) { d =>
      for (cancel <- cancels) {
        boot(d)
        drive(d)
        d.clock.step(3)
        d.io.WaitCycles.expect(3.U)
        d.io.WFITimeoutM.expect(true.B)
        drive(d, cancel)
        d.io.WaitCycles.expect(3.U) // 计数清零是同步操作。
        d.io.WFIStallM.expect(false.B)
        d.io.WFITimeoutM.expect(false.B)
        d.clock.step()
        d.io.WaitCycles.expect(0.U)
        drive(d)
        d.io.WFIStallM.expect(true.B)
        d.io.WFITimeoutM.expect(false.B)
        d.clock.step()
        d.io.WaitCycles.expect(1.U)
      }
    }
  }

  it should "retain timeout through its accepted trap until synchronous count clearing" in {
    test(new WFIHarness(3)) { d =>
      boot(d)
      drive(d)
      d.clock.step(2)
      drive(d, Inputs(trap = true))
      d.io.WFIStallM.expect(false.B)
      d.io.WFITimeoutM.expect(false.B)
      d.io.WaitCycles.expect(2.U)
      d.clock.step()
      d.io.WaitCycles.expect(0.U)
      drive(d)
      d.clock.step(3)
      d.io.WFITimeoutM.expect(true.B)
      drive(d, Inputs(trap = true))
      d.io.WFITimeoutM.expect(true.B) // 自身 Trap 不组合撤销 Timeout，防止反馈环。
      d.io.WFIStallM.expect(false.B)
      d.io.WaitCycles.expect(3.U)
      d.clock.step()
      d.io.WaitCycles.expect(0.U)
      d.io.WFITimeoutM.expect(false.B)
    }
  }

  it should "wake immediately for pending and clear the count at the next edge" in {
    test(new WFIHarness(3)) { d =>
      boot(d)
      drive(d, Inputs(pending = true))
      d.io.WFIStallM.expect(false.B)
      d.clock.step(5)
      d.io.WaitCycles.expect(0.U)
      drive(d)
      d.clock.step(2)
      d.io.WaitCycles.expect(2.U)
      drive(d, Inputs(pending = true))
      d.io.WFIStallM.expect(false.B)
      d.io.WaitCycles.expect(2.U)
      d.clock.step()
      d.io.WaitCycles.expect(0.U)
      d.io.WFITimeoutM.expect(false.B)
      drive(d)
      d.clock.step(3)
      d.io.WFITimeoutM.expect(true.B)
      drive(d, Inputs(pending = true))
      d.io.WFIStallM.expect(false.B)
      d.clock.step()
      d.io.WaitCycles.expect(0.U)
      d.io.WFITimeoutM.expect(false.B)
    }
  }

  it should "reset synchronously with priority over an active waiting instruction" in {
    test(new WFIHarness(3)) { d =>
      boot(d)
      drive(d)
      d.clock.step(2)
      d.reset.poke(true.B)
      d.io.WaitCycles.expect(2.U)
      d.clock.step()
      d.io.WaitCycles.expect(0.U)
      d.clock.step(2)
      d.io.WaitCycles.expect(0.U)
      d.reset.poke(false.B)
      d.clock.step(3)
      d.io.WaitCycles.expect(3.U)
      d.io.WFITimeoutM.expect(true.B)
      d.reset.poke(true.B)
      d.io.WaitCycles.expect(3.U)
      d.clock.step()
      d.io.WaitCycles.expect(0.U)
      d.io.WFITimeoutM.expect(false.B)
    }
  }

  it should "start a fresh deadline after a bubble or a completed pending wake" in {
    test(new WFIHarness(3)) { d =>
      boot(d)
      for (gap <- Seq(Inputs(valid = false), Inputs(wfi = false), Inputs(pending = true))) {
        drive(d)
        d.clock.step(3)
        d.io.WFITimeoutM.expect(true.B)
        drive(d, gap)
        d.clock.step()
        d.io.WaitCycles.expect(0.U)
        drive(d)
        d.io.WFIStallM.expect(true.B)
        d.clock.step(2)
        d.io.WaitCycles.expect(2.U)
        d.io.WFITimeoutM.expect(false.B)
        d.clock.step()
        d.io.WFITimeoutM.expect(true.B)
      }
    }
  }

  it should "match consecutive wait lengths with random wake cancel and reset events" in {
    for (deadline <- Seq(1, 3, 8)) {
      test(new WFIHarness(deadline)) { d =>
        boot(d)
        val random = new Random(0x10500073L + deadline)
        var runLength = 0
        for (cycle <- 0 until 512) {
          val a = Inputs(valid = random.nextInt(8) != 0, wfi = random.nextInt(8) != 0,
            flush = random.nextInt(20) == 0, trap = random.nextInt(20) == 0,
            pending = random.nextInt(12) == 0)
          val resetting = cycle % 41 == 0
          val active = a.valid && a.wfi && !a.flush && !a.trap
          val timeoutEligible = a.valid && a.wfi && !a.flush
          drive(d, a)
          d.reset.poke(resetting.B)
          d.io.WaitCycles.expect(math.min(runLength, deadline).U)
          d.io.WFITimeoutM.expect((timeoutEligible && runLength >= deadline).B)
          d.io.WFIStallM.expect((active && !a.pending && runLength < deadline).B)
          d.clock.step()
          runLength = if (resetting || !active || a.pending) 0 else runLength + 1
          d.io.WaitCycles.expect(math.min(runLength, deadline).U)
          d.io.WFITimeoutM.expect((timeoutEligible && runLength >= deadline).B)
          d.io.WFIStallM.expect((active && !a.pending && runLength < deadline).B)
        }
      }
    }
  }

  it should "emit only explicit synchronous clock reset and width-correct waiting ports" in {
    for ((deadline, width) <- Seq(1 -> 1, 3 -> 2, 8 -> 4, 65536 -> 17,
         (1 << 24) -> 25)) {
      val rtl = (new ChiselStage).emitVerilog(new WFI(deadline),
        Array("--target-dir", Files.createTempDirectory("wfi-rtl-").toString))
      val pattern = "(?m)^\\s*(input|output)\\s+(?:\\[(\\d+):0\\]\\s+)?(\\w+)".r
      val ports = pattern.findAllMatchIn(rtl).map { m =>
        m.group(3) -> (m.group(1), Option(m.group(2)).map(_.toInt + 1).getOrElse(1))
      }.toMap
      val inputs = Seq("clk", "reset", "io_ValidM", "io_wfiM", "io_FlushM", "io_TrapM",
        "io_IntPendingM").map(_ -> ("input", 1)).toMap
      assert(ports == inputs ++ Map("io_WFIStallM" -> ("output", 1),
        "io_WFITimeoutM" -> ("output", 1), "io_WaitCycles" -> ("output", width)))
      assert(rtl.contains("always @(posedge clk)"))
      assert(!rtl.contains("posedge reset") && !rtl.contains("negedge"))
    }
  }

  it should "reject zero negative and excessively large waiting thresholds" in {
    for (deadline <- Seq(Int.MinValue, -1, 0, (1 << 24) + 1, Int.MaxValue)) {
      intercept[IllegalArgumentException] {
        (new ChiselStage).emitChirrtl(new WFI(deadline))
      }
    }
  }
}
