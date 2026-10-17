package riscvpipeline.ebu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

private class ControllerInputHarness(saveEnabled: Boolean) extends Module {
  val io = IO(new ControllerInputIO)
  val dut = Module(new ControllerInput(saveEnabled))
  dut.clk := clock
  dut.reset := reset.asBool
  io <> dut.io
}

class ControllerInputSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  private def defaults(d: ControllerInputHarness): Unit = {
    d.io.Save.poke(false.B)
    d.io.Restore.poke(false.B)
    d.io.Disable.poke(false.B)
    d.io.HREADYIn.poke(true.B)
    d.io.HTRANSIn.poke(2.U)
    d.io.HWRITEIn.poke(true.B)
    d.io.HSIZEIn.poke(2.U)
    d.io.HBURSTIn.poke(0.U)
    d.io.HADDRIn.poke("h300001004".U)
    d.reset.poke(true.B)
    d.clock.step()
    d.reset.poke(false.B)
  }

  behavior of "ControllerInput"
  it should "save the complete address phase and restore it independently of live inputs" in {
    test(new ControllerInputHarness(true)) { d =>
      defaults(d)
      d.io.Save.poke(true.B)
      d.clock.step()
      d.io.Save.poke(false.B)
      d.io.Restore.poke(true.B)
      d.io.HADDRIn.poke(16.U)
      d.io.HSIZEIn.poke(0.U)
      d.io.HWRITEIn.poke(false.B)
      d.io.HTRANSIn.poke(0.U)
      d.clock.step(3)
      d.io.HADDROut.expect("h300001004".U)
      d.io.HSIZEOut.expect(2.U)
      d.io.HWRITEOut.expect(true.B)
      d.io.HTRANSOut.expect(2.U)
      d.io.Request.expect(true.B)
      d.io.Restore.poke(false.B)
      d.io.HADDROut.expect(16.U)
      d.io.Request.expect(false.B)
    }
  }
  for (enabled <- Seq(false, true)) {
    it should s"qualify ready solely with Disable and HREADYIn ($enabled)" in {
      test(new ControllerInputHarness(enabled)) { d =>
        defaults(d)
        for (disable <- Seq(false, true); ready <- Seq(false, true)) {
          d.io.Disable.poke(disable.B)
          d.io.HREADYIn.poke(ready.B)
          d.io.HREADYOut.expect((ready && !disable).B)
          d.io.HADDROut.expect("h300001004".U)
        }
      }
    }
  }
  it should "bypass storage with save disabled even when Restore is asserted" in {
    test(new ControllerInputHarness(false)) { d =>
      defaults(d)
      d.io.Save.poke(true.B)
      d.clock.step()
      d.io.Restore.poke(true.B)
      d.io.HADDRIn.poke(64.U)
      d.io.HADDROut.expect(64.U)
      d.io.HTRANSIn.poke(0.U)
      d.io.Request.expect(false.B)
    }
  }
}
