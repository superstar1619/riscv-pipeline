package riscvpipeline.ebu

import chisel3._
import chisel3.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.Files

private class BusFSMHarness(readOnly: Boolean) extends Module {
  val io = IO(new BusFSMIO)
  val dut = Module(new BusFSM(readOnly))
  dut.clk := clock
  dut.reset := reset.asBool
  io <> dut.io
}

class BusFSMSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  // 独立定义接口编码，不从 DUT 导入请求/HTRANS 常量作为预期。
  private val NoRequest = 0
  private val Write = 1
  private val Read = 2
  private val InvalidRequest = 3

  private def drive(d: BusFSMHarness, rw: Int = NoRequest, ready: Boolean = true,
    stall: Boolean = false, flush: Boolean = false): Unit = {
    d.io.BusRW.poke(rw.U)
    d.io.HREADY.poke(ready.B)
    d.io.Stall.poke(stall.B)
    d.io.Flush.poke(flush.B)
  }

  private def check(d: BusFSMHarness, transfer: Int = 0, busy: Boolean = false,
    committed: Boolean = false, capture: Boolean = false, write: Boolean = false): Unit = {
    d.io.HTRANS.expect(transfer.U)
    d.io.BusStall.expect(busy.B)
    d.io.BusCommitted.expect(committed.B)
    d.io.CaptureEn.expect(capture.B)
    d.io.HWRITE.expect(write.B)
  }

  private def boot(d: BusFSMHarness): Unit = {
    drive(d)
    d.reset.poke(true.B)
    d.clock.step()
    d.reset.poke(false.B)
    check(d)
  }

  behavior of "BusFSM"

  for (readOnly <- Seq(false, true)) {
    val mode = if (readOnly) "IFU" else "LSU"

    it should s"remain idle without a request in $mode mode" in {
      test(new BusFSMHarness(readOnly)) { d =>
        boot(d)
        for (ready <- Seq(false, true); stall <- Seq(false, true)) {
          drive(d, ready = ready, stall = stall)
          check(d)
          d.clock.step(3)
          check(d)
        }
      }
    }

    // 提前接受地址或把 Data 当作已完成都会破坏下列逐拍预期。
    it should s"wait for address acceptance and capture only a ready read in $mode mode" in {
      test(new BusFSMHarness(readOnly)).withAnnotations(Seq(WriteVcdAnnotation)) { d =>
        boot(d)
        drive(d, Read, ready = false, stall = true)
        for (_ <- 0 until 3) {
          check(d, busy = true)
          d.clock.step()
        }
        drive(d, Read, stall = true)
        check(d, transfer = 2, busy = true)
        d.clock.step()
        drive(d, Read, ready = false, stall = true)
        for (_ <- 0 until 4) {
          check(d, busy = true, committed = true)
          d.clock.step()
        }
        drive(d, Read, stall = true)
        check(d, busy = true, committed = true, capture = true)
        d.clock.step()
        check(d, committed = !readOnly)
      }
    }

    it should s"hold a completed request without reissuing in $mode mode" in {
      test(new BusFSMHarness(readOnly)) { d =>
        boot(d)
        drive(d, Read)
        d.clock.step()
        check(d, busy = true, committed = true, capture = true)
        d.clock.step()
        for (ready <- Seq(false, true)) {
          drive(d, Read, ready = ready, stall = true)
          for (_ <- 0 until 4) {
            check(d, committed = !readOnly)
            d.clock.step()
          }
        }
        drive(d, Read)
        check(d, committed = !readOnly)
        d.clock.step()
        check(d, transfer = 2, busy = true)
      }
    }

    it should s"cancel only an unaccepted address under flush in $mode mode" in {
      test(new BusFSMHarness(readOnly)) { d =>
        boot(d)
        for (ready <- Seq(false, true)) {
          drive(d, Read, ready = ready, stall = true, flush = true)
          check(d)
          d.clock.step(3)
          check(d)
        }
        drive(d)
        d.clock.step()
        check(d)
        drive(d, Read)
        check(d, transfer = 2, busy = true)
      }
    }

    it should s"finish an accepted read despite request and flush changes in $mode mode" in {
      test(new BusFSMHarness(readOnly)) { d =>
        boot(d)
        drive(d, Read)
        d.clock.step()
        drive(d, Write, ready = false, stall = true, flush = true)
        d.clock.step(3)
        check(d, busy = true, committed = true)
        drive(d, Write, stall = true, flush = true)
        check(d, busy = true, committed = true, capture = true)
        d.clock.step()
        check(d, committed = !readOnly)
        d.clock.step(3)
        check(d, committed = !readOnly)
      }
    }

    it should s"reset synchronously without combinational gating in $mode mode" in {
      test(new BusFSMHarness(readOnly)) { d =>
        val requests = if (readOnly) Seq(Read) else Seq(Read, Write)
        for (rw <- requests; phase <- 0 to 2) {
          boot(d)
          drive(d, rw)
          d.clock.step(phase)

          def checkPhase(): Unit = phase match {
            case 0 => check(d, transfer = 2, busy = true, write = rw == Write)
            case 1 => check(d, busy = true, committed = true,
              capture = rw == Read, write = rw == Write)
            case 2 => check(d, committed = !readOnly)
          }

          // 无上升沿的 reset 脉冲不改变寄存器或组合控制输出。
          d.reset.poke(true.B)
          checkPhase()
          d.reset.poke(false.B)
          checkPhase()
          d.reset.poke(true.B)
          d.clock.step(2)
          // reset 沿回 Adr；上层请求仍在，Adr 输出仍可为 NONSEQ。
          check(d, transfer = 2, busy = true, write = rw == Write)
          drive(d)
          check(d)
          d.reset.poke(false.B)
          check(d)
          drive(d, Read)
          check(d, transfer = 2, busy = true)
        }
      }
    }

    it should s"ignore illegal request encodings in $mode mode" in {
      test(new BusFSMHarness(readOnly)) { d =>
        boot(d)
        val rejected = if (readOnly) Seq(InvalidRequest, Write) else Seq(InvalidRequest)
        for (rw <- rejected; ready <- Seq(false, true)) {
          drive(d, rw, ready = ready)
          check(d)
          d.clock.step(3)
          check(d)
        }
        drive(d, Read)
        check(d, transfer = 2, busy = true)
      }
    }

    it should s"complete consecutive requests across bounded waits in $mode mode" in {
      test(new BusFSMHarness(readOnly)) { d =>
        boot(d)
        val requests = if (readOnly) Seq(Read) else Seq(Read, Write)
        for (rw <- requests; addressWait <- 0 to 2; dataWait <- 0 to 2;
             parentWait <- 0 to 2) {
          drive(d, rw, ready = false)
          for (_ <- 0 until addressWait) {
            check(d, busy = true, write = rw == Write)
            d.clock.step()
          }
          drive(d, rw)
          check(d, transfer = 2, busy = true, write = rw == Write)
          d.clock.step()
          drive(d, rw, ready = false)
          for (_ <- 0 until dataWait) {
            check(d, busy = true, committed = true, write = rw == Write)
            d.clock.step()
          }
          drive(d, rw)
          check(d, busy = true, committed = true, capture = rw == Read, write = rw == Write)
          d.clock.step()
          drive(d, rw, stall = true)
          for (_ <- 0 until parentWait) {
            check(d, committed = !readOnly)
            d.clock.step()
          }
          drive(d)
          check(d, committed = !readOnly)
          d.clock.step()
          check(d)
        }
      }
    }
  }

  it should "retain an accepted write direction and never capture write responses" in {
    test(new BusFSMHarness(false)) { d =>
      boot(d)
      drive(d, Write)
      check(d, transfer = 2, busy = true, write = true)
      d.clock.step()
      for (rw <- Seq(NoRequest, Read, InvalidRequest)) {
        drive(d, rw, ready = false, flush = true)
        check(d, busy = true, committed = true, write = true)
        d.clock.step(2)
      }
      drive(d, Read, stall = true, flush = true)
      check(d, busy = true, committed = true, write = true)
      d.clock.step()
      check(d, committed = true)
      d.clock.step(3)
      check(d, committed = true)
      drive(d)
      d.clock.step()
      check(d)
    }
  }

  // 写方向只能置位、不能在下一次读请求清零时，此场景必须失败。
  it should ("switch from a completed write to a read " +
    "without retaining the write direction") in {
    test(new BusFSMHarness(false)) { d =>
      boot(d)
      drive(d, Write)
      d.clock.step()
      check(d, busy = true, committed = true, write = true)
      d.clock.step()
      drive(d)
      check(d, committed = true)
      d.clock.step()
      drive(d, Read)
      check(d, transfer = 2, busy = true)
      d.clock.step()
      check(d, busy = true, committed = true, capture = true)
      d.clock.step()
      check(d, committed = true)
    }
  }

  it should "emit identical explicit synchronous interfaces for both configurations" in {
    for (readOnly <- Seq(false, true)) {
      val rtl = (new ChiselStage).emitVerilog(new BusFSM(readOnly),
        Array("--target-dir", Files.createTempDirectory("bus-fsm-rtl-").toString))
      val pattern = "(?m)^\\s*(input|output)\\s+(?:\\[(\\d+):0\\]\\s+)?(\\w+)".r
      val ports = pattern.findAllMatchIn(rtl).map { m =>
        m.group(3) -> (m.group(1), Option(m.group(2)).map(_.toInt + 1).getOrElse(1))
      }.toMap
      val inputs = Seq("clk", "reset", "io_Stall", "io_Flush", "io_HREADY")
        .map(_ -> ("input", 1)).toMap + ("io_BusRW" -> ("input", 2))
      val outputs = Seq("io_CaptureEn", "io_BusStall", "io_BusCommitted", "io_HWRITE")
        .map(_ -> ("output", 1)).toMap + ("io_HTRANS" -> ("output", 2))
      assert(ports == inputs ++ outputs)
      assert(rtl.contains("always @(posedge clk)"))
      assert(!rtl.contains("posedge reset") && !rtl.contains("negedge"))
    }
  }
}
