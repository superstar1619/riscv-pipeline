package riscvpipeline.privileged

import riscvpipeline.TestEncoding.{Privilege}
import chisel3._
import chisel3.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.Files
import scala.language.reflectiveCalls
import scala.util.Random

private class PrivModeHarness(s: Boolean = true) extends Module {
  val io = IO(new PrivModeIO)
  val dut = Module(new PrivMode(s))
  dut.clk := clock
  dut.reset := reset.asBool
  io <> dut.io
}

// 只在测试层闭环连接当前模式、状态栈与合法返回译码。
private class PrivModeStatusHarness(s: Boolean = true) extends Module {
  val io = IO(new Bundle {
    val Instr = Input(UInt(32.W))
    val Valid = Input(Bool())
    val Stall = Input(Bool())
    val Flush = Input(Bool())
    val Trap = Input(Bool())
    val Delegate = Input(Bool())
    val WriteStatus = Input(Bool())
    val Word = Input(UInt(32.W))
    val Fault = Input(Bool())
    val Mode = Output(UInt(2.W))
    val Next = Output(UInt(2.W))
    val Status = Output(UInt(32.W))
    val Illegal = Output(Bool())
    val MRet = Output(Bool())
    val SRet = Output(Bool())
  })
  val mode = Module(new PrivMode(s))
  val status = Module(new CSRStatus(s))
  val decode = Module(new PrivDecode(s))
  mode.clk := clock
  status.clk := clock
  mode.reset := reset.asBool
  status.reset := reset.asBool
  mode.io.ValidM := io.Valid
  mode.io.StallW := io.Stall
  mode.io.FlushM := io.Flush
  mode.io.TrapM := io.Trap
  mode.io.DelegateM := io.Delegate
  mode.io.mretM := decode.io.mretM
  mode.io.sretM := decode.io.sretM
  mode.io.STATUS_MPP := status.io.STATUS_MPP
  mode.io.STATUS_SPP := status.io.STATUS_SPP
  mode.io.STATUS_TSR := status.io.STATUS_TSR
  status.io.ValidM := io.Valid
  status.io.StallW := io.Stall
  status.io.FlushM := io.Flush
  status.io.TrapM := io.Trap
  status.io.PrivilegeModeW := mode.io.PrivilegeModeW
  status.io.NextPrivilegeModeM := mode.io.NextPrivilegeModeM
  status.io.mretM := decode.io.mretM
  status.io.sretM := decode.io.sretM
  status.io.WriteMSTATUSM := io.WriteStatus
  status.io.WriteMSTATUSHM := false.B
  status.io.WriteSSTATUSM := false.B
  status.io.CSRWriteValM := io.Word
  status.io.SelHPTW := false.B
  decode.io.InstrM := io.Instr
  decode.io.ValidM := io.Valid
  decode.io.PrivilegeModeW := mode.io.PrivilegeModeW
  decode.io.STATUS_TSR := status.io.STATUS_TSR
  decode.io.STATUS_TW := status.io.STATUS_TW
  decode.io.STATUS_TVM := status.io.STATUS_TVM
  decode.io.WFITimeoutM := false.B
  decode.io.IllegalIEUInstrM := io.Fault
  decode.io.IllegalCSRAccessM := false.B
  io.Mode := mode.io.PrivilegeModeW
  io.Next := mode.io.NextPrivilegeModeM
  io.Status := status.io.MSTATUS_REGW
  io.Illegal := decode.io.IllegalInstrFaultM
  io.MRet := decode.io.mretM
  io.SRet := decode.io.sretM
}

class PrivModeSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  private case class Inputs(valid: Boolean = true, stall: Boolean = false,
    flush: Boolean = false, trap: Boolean = false, mr: Boolean = false,
    sr: Boolean = false, delegate: Boolean = false, mpp: Int = Privilege.USER,
    spp: Boolean = false, tsr: Boolean = false)
  private def drive(d: PrivModeHarness, a: Inputs): Unit = {
    d.io.ValidM.poke(a.valid.B)
    d.io.StallW.poke(a.stall.B)
    d.io.FlushM.poke(a.flush.B)
    d.io.TrapM.poke(a.trap.B)
    d.io.mretM.poke(a.mr.B)
    d.io.sretM.poke(a.sr.B)
    d.io.DelegateM.poke(a.delegate.B)
    d.io.STATUS_MPP.poke(a.mpp.U)
    d.io.STATUS_SPP.poke(a.spp.B)
    d.io.STATUS_TSR.poke(a.tsr.B)
  }
  private def boot(d: PrivModeHarness): Unit = {
    drive(d, Inputs(valid = false))
    d.reset.poke(true.B)
    d.clock.step()
    d.reset.poke(false.B)
    d.io.PrivilegeModeW.expect(Privilege.MACHINE.U)
    d.io.NextPrivilegeModeM.expect(Privilege.MACHINE.U)
  }
  private def enter(d: PrivModeHarness, target: Int): Unit = {
    boot(d)
    drive(d, Inputs(mr = true, mpp = target))
    d.clock.step()
    drive(d, Inputs())
    d.io.PrivilegeModeW.expect(target.U)
  }
  // 用模式集合和优先事件表描述参考状态机，不调用生产代码。
  private def candidate(s: Boolean, current: Int, a: Inputs): Int = {
    val supported = if (s) Set(Privilege.USER, Privilege.SUPERVISOR,
      Privilege.MACHINE) else Set(Privilege.USER, Privilege.MACHINE)
    if (a.trap) {
      if (s && a.delegate && Set(Privilege.USER,
        Privilege.SUPERVISOR)(current)) Privilege.SUPERVISOR else Privilege.MACHINE
    } else if (a.mr) {
      if (current == Privilege.MACHINE && supported(a.mpp)) a.mpp else current
    } else if (a.sr) {
      if (s && (current == Privilege.MACHINE || (current == Privilege.SUPERVISOR && !a.tsr))) {
        if (a.spp) Privilege.SUPERVISOR else Privilege.USER
      } else current
    } else current
  }

  behavior of "PrivMode"

  it should "reset synchronously to M with priority over stalled return requests" in {
    test(new PrivModeHarness) { d =>
      enter(d, Privilege.USER)
      drive(d, Inputs(mr = true, sr = true, trap = true, delegate = true,
        valid = false, stall = true, flush = true))
      d.reset.poke(true.B)
      d.io.PrivilegeModeW.expect(Privilege.USER.U)
      d.io.NextPrivilegeModeM.expect(Privilege.SUPERVISOR.U)
      d.clock.step()
      d.io.PrivilegeModeW.expect(Privilege.MACHINE.U)
      d.io.NextPrivilegeModeM.expect(Privilege.MACHINE.U)
    }
  }

  it should "hold the current mode with no event despite delegate and status changes" in {
    for (s <- Seq(false, true)) {
      test(new PrivModeHarness(s)) { d =>
        for (current <- (if (s) Seq(Privilege.USER, Privilege.SUPERVISOR,
          Privilege.MACHINE) else Seq(Privilege.USER, Privilege.MACHINE))) {
          enter(d, current)
          for (mpp <- 0 to 3; spp <- Seq(false, true); delegate <- Seq(false, true)) {
            drive(d, Inputs(mpp = mpp, spp = spp, delegate = delegate, tsr = true))
            d.io.NextPrivilegeModeM.expect(current.U)
            d.clock.step()
            d.io.PrivilegeModeW.expect(current.U)
          }
        }
      }
    }
  }

  it should "return from M to each supported MPP and reject unsupported encodings" in {
    for (s <- Seq(false, true)) {
      test(new PrivModeHarness(s)) { d =>
        for (mpp <- 0 to 3) {
          boot(d)
          val expected = if (mpp == Privilege.RESERVED || (!s &&
            mpp == Privilege.SUPERVISOR)) Privilege.MACHINE else mpp
          drive(d, Inputs(mr = true, mpp = mpp))
          d.io.NextPrivilegeModeM.expect(expected.U)
          d.io.PrivilegeModeW.expect(Privilege.MACHINE.U)
          d.clock.step()
          d.io.PrivilegeModeW.expect(expected.U)
        }
      }
    }
  }

  it should "take delegated traps from U or S and retain M for all M traps" in {
    for (s <- Seq(false, true)) {
      test(new PrivModeHarness(s)) { d =>
        for (current <- (if (s) Seq(Privilege.USER, Privilege.SUPERVISOR,
          Privilege.MACHINE) else Seq(Privilege.USER, Privilege.MACHINE));
             delegate <- Seq(false, true)) {
          enter(d, current)
          val target = if (s && delegate &&
            current != Privilege.MACHINE) Privilege.SUPERVISOR else Privilege.MACHINE
          drive(d, Inputs(trap = true, delegate = delegate, mr = true, sr = true))
          d.io.NextPrivilegeModeM.expect(target.U)
          d.io.PrivilegeModeW.expect(current.U)
          d.clock.step()
          d.io.PrivilegeModeW.expect(target.U)
        }
      }
    }
  }

  it should "permit SRET in M regardless of TSR and in S only when TSR is clear" in {
    for (s <- Seq(false, true)) {
      test(new PrivModeHarness(s)) { d =>
        for (current <- (if (s) Seq(Privilege.USER, Privilege.SUPERVISOR,
          Privilege.MACHINE) else Seq(Privilege.USER, Privilege.MACHINE));
             tsr <- Seq(false, true); spp <- Seq(false, true)) {
          enter(d, current)
          val target = if (s && (current == Privilege.MACHINE ||
            (current == Privilege.SUPERVISOR && !tsr))) {
            if (spp) Privilege.SUPERVISOR else Privilege.USER
          } else current
          drive(d, Inputs(sr = true, tsr = tsr, spp = spp))
          d.io.NextPrivilegeModeM.expect(target.U)
          d.clock.step()
          d.io.PrivilegeModeW.expect(target.U)
        }
      }
    }
  }

  it should "preserve raw MRET priority even when its source or MPP is illegal" in {
    test(new PrivModeHarness) { d =>
      boot(d)
      drive(d, Inputs(mr = true, sr = true, mpp = Privilege.RESERVED, spp = false))
      d.io.NextPrivilegeModeM.expect(Privilege.MACHINE.U)
      d.clock.step()
      d.io.PrivilegeModeW.expect(Privilege.MACHINE.U)
      enter(d, Privilege.SUPERVISOR)
      drive(d, Inputs(mr = true, sr = true, mpp = Privilege.USER, spp = false))
      d.io.NextPrivilegeModeM.expect(Privilege.SUPERVISOR.U)
      d.clock.step()
      d.io.PrivilegeModeW.expect(Privilege.SUPERVISOR.U)
      enter(d, Privilege.USER)
      drive(d, Inputs(mr = true, sr = true, mpp = Privilege.MACHINE, spp = true))
      d.io.NextPrivilegeModeM.expect(Privilege.USER.U)
      d.clock.step()
      d.io.PrivilegeModeW.expect(Privilege.USER.U)
    }
  }

  it should "hold registered state for every invalid stall and flush combination" in {
    test(new PrivModeHarness) { d =>
      for (event <- 0 until 3; valid <- Seq(false, true); stall <- Seq(false, true);
           flush <- Seq(false, true)) {
        val source = if (event == 0) Privilege.USER else Privilege.MACHINE
        val target = if (event == 1) Privilege.USER else Privilege.SUPERVISOR
        enter(d, source)
        drive(d, Inputs(valid = valid, stall = stall, flush = flush,
          trap = event == 0, delegate = true, mr = event == 1,
          sr = event == 2, spp = true))
        d.io.NextPrivilegeModeM.expect(target.U)
        d.clock.step()
        d.io.PrivilegeModeW.expect((if (valid && !stall && !flush) target else source).U)
      }
    }
  }

  it should "retain a pending proposal through stalls and commit it once on release" in {
    test(new PrivModeHarness) { d =>
      enter(d, Privilege.USER)
      drive(d, Inputs(trap = true, delegate = true, stall = true))
      for (_ <- 0 until 4) {
        d.io.NextPrivilegeModeM.expect(Privilege.SUPERVISOR.U)
        d.clock.step()
        d.io.PrivilegeModeW.expect(Privilege.USER.U)
      }
      drive(d, Inputs(trap = true, delegate = true))
      d.clock.step()
      d.io.PrivilegeModeW.expect(Privilege.SUPERVISOR.U)
      drive(d, Inputs(sr = true, spp = false, flush = true))
      d.clock.step()
      d.io.PrivilegeModeW.expect(Privilege.SUPERVISOR.U)
      drive(d, Inputs())
      d.clock.step()
      d.io.PrivilegeModeW.expect(Privilege.SUPERVISOR.U)
    }
  }

  it should "match every raw event status and reachable current-mode combination" in {
    for (s <- Seq(false, true)) {
      test(new PrivModeHarness(s)) { d =>
        for (current <- (if (s) Seq(Privilege.USER, Privilege.SUPERVISOR,
          Privilege.MACHINE) else Seq(Privilege.USER, Privilege.MACHINE))) {
          enter(d, current)
          for (flags <- 0 until 64; mpp <- 0 to 3) {
            val a = Inputs(valid = false, trap = (flags & 1) != 0,
              mr = (flags & 2) != 0, sr = (flags & 4) != 0,
              delegate = (flags & 8) != 0, spp = (flags & 16) != 0,
              tsr = (flags & 32) != 0, mpp = mpp)
            drive(d, a)
            d.io.NextPrivilegeModeM.expect(candidate(s, current, a).U)
            d.io.PrivilegeModeW.expect(current.U)
          }
        }
      }
    }
  }

  private def integrationDrive(d: PrivModeStatusHarness, instr: BigInt = 0x13,
                              word: BigInt = 0, write: Boolean = false,
                              trap: Boolean = false, delegate: Boolean = false,
                              valid: Boolean = true, stall: Boolean = false,
                              flush: Boolean = false, fault: Boolean = false): Unit = {
    d.io.Instr.poke(instr.U)
    d.io.Word.poke(word.U)
    d.io.WriteStatus.poke(write.B)
    d.io.Trap.poke(trap.B)
    d.io.Delegate.poke(delegate.B)
    d.io.Valid.poke(valid.B)
    d.io.Stall.poke(stall.B)
    d.io.Flush.poke(flush.B)
    d.io.Fault.poke(fault.B)
  }
  private def integrationBoot(d: PrivModeStatusHarness): Unit = {
    integrationDrive(d, valid = false)
    d.reset.poke(true.B)
    d.clock.step()
    d.reset.poke(false.B)
    d.io.Mode.expect(Privilege.MACHINE.U)
    d.io.Status.expect(0.U)
  }

  it should "connect real status and decode for U S M trap and return sequences" in {
    test(new PrivModeStatusHarness) { d =>
      integrationBoot(d)
      integrationDrive(d, word = 0x20082, write = true)
      d.clock.step()
      integrationDrive(d, instr = 0x30200073)
      d.io.MRet.expect(true.B)
      d.io.Next.expect(Privilege.USER.U)
      d.io.Mode.expect(Privilege.MACHINE.U)
      d.clock.step()
      d.io.Mode.expect(Privilege.USER.U)
      d.io.Status.expect(0x8a.U)
      integrationDrive(d, trap = true, delegate = true)
      d.io.Next.expect(Privilege.SUPERVISOR.U)
      d.clock.step()
      d.io.Mode.expect(Privilege.SUPERVISOR.U)
      d.io.Status.expect(0xa8.U)
      integrationDrive(d, instr = 0x10200073)
      d.io.SRet.expect(true.B)
      d.io.Next.expect(Privilege.USER.U)
      d.clock.step()
      d.io.Mode.expect(Privilege.USER.U)
      d.io.Status.expect(0xaa.U)
      integrationDrive(d, trap = true)
      d.clock.step()
      d.io.Mode.expect(Privilege.MACHINE.U)
      d.io.Status.expect(0xa2.U)
      integrationDrive(d, instr = 0x30200073)
      d.io.Next.expect(Privilege.USER.U)
      d.clock.step()
      d.io.Mode.expect(Privilege.USER.U)
      d.io.Status.expect(0xaa.U)
    }
  }

  it should "consume old SPP before real status clears it on a horizontal S trap return" in {
    test(new PrivModeStatusHarness) { d =>
      integrationBoot(d)
      integrationDrive(d, word = 0x882, write = true)
      d.clock.step()
      integrationDrive(d, instr = 0x30200073)
      d.clock.step()
      d.io.Mode.expect(Privilege.SUPERVISOR.U)
      integrationDrive(d, trap = true, delegate = true)
      d.clock.step()
      d.io.Mode.expect(Privilege.SUPERVISOR.U)
      d.io.Status.expect(0x1a8.U)
      integrationDrive(d, instr = 0x10200073)
      d.io.Next.expect(Privilege.SUPERVISOR.U)
      d.clock.step()
      d.io.Mode.expect(Privilege.SUPERVISOR.U)
      d.io.Status.expect(0xaa.U)
      d.io.Next.expect(Privilege.USER.U)
      d.clock.step()
      d.io.Mode.expect(Privilege.USER.U)
    }
  }

  it should "agree with real TSR checks for SRET in M and S and suppress faulted returns" in {
    test(new PrivModeStatusHarness) { d =>
      integrationBoot(d)
      integrationDrive(d, word = 0x420100, write = true)
      d.clock.step()
      integrationDrive(d, instr = 0x10200073, fault = true)
      d.io.Illegal.expect(true.B)
      d.io.SRet.expect(false.B)
      d.clock.step()
      d.io.Mode.expect(Privilege.MACHINE.U)
      d.io.Status.expect(0x420100.U)
      integrationDrive(d, instr = 0x10200073)
      d.io.Illegal.expect(false.B)
      d.io.SRet.expect(true.B)
      d.io.Next.expect(Privilege.SUPERVISOR.U)
      d.clock.step()
      d.io.Mode.expect(Privilege.SUPERVISOR.U)
      d.io.Status.expect(0x400020.U)
      d.io.Illegal.expect(true.B)
      d.io.SRet.expect(false.B)
      d.clock.step()
      d.io.Mode.expect(Privilege.SUPERVISOR.U)
      d.io.Status.expect(0x400020.U)
    }
  }

  it should "keep status and mode atomic under gates reset and trap over return priority" in {
    test(new PrivModeStatusHarness) { d =>
      integrationBoot(d)
      integrationDrive(d, word = 0x82, write = true)
      d.clock.step()
      for (blocked <- 0 until 3) {
        integrationDrive(d, instr = 0x30200073, valid = blocked != 0,
          stall = blocked == 1, flush = blocked == 2)
        d.clock.step()
        d.io.Mode.expect(Privilege.MACHINE.U)
        d.io.Status.expect(0x82.U)
      }
      integrationDrive(d, instr = 0x30200073, trap = true, delegate = true)
      d.io.Next.expect(Privilege.MACHINE.U)
      d.clock.step()
      d.io.Mode.expect(Privilege.MACHINE.U)
      d.io.Status.expect(0x1802.U)
      integrationDrive(d, instr = 0x30200073)
      d.clock.step()
      d.io.Mode.expect(Privilege.MACHINE.U)
      d.io.Status.expect(0x82.U)
      integrationDrive(d, instr = 0x30200073, stall = true, flush = true)
      d.reset.poke(true.B)
      d.clock.step()
      d.io.Mode.expect(Privilege.MACHINE.U)
      d.io.Status.expect(0.U)
    }
  }

  it should "connect real U M status while S support is disabled" in {
    test(new PrivModeStatusHarness(false)) { d =>
      integrationBoot(d)
      integrationDrive(d, instr = 0x10200073)
      d.io.Illegal.expect(true.B)
      d.clock.step()
      d.io.Mode.expect(Privilege.MACHINE.U)
      integrationDrive(d, word = 0x20080, write = true)
      d.clock.step()
      integrationDrive(d, instr = 0x30200073)
      d.clock.step()
      d.io.Mode.expect(Privilege.USER.U)
      d.io.Status.expect(0x88.U)
      integrationDrive(d, trap = true, delegate = true)
      d.clock.step()
      d.io.Mode.expect(Privilege.MACHINE.U)
      d.io.Status.expect(0x80.U)
    }
  }

  it should "match a seeded model across both S configurations without illegal modes" in {
    for (s <- Seq(false, true)) {
      test(new PrivModeHarness(s)) { d =>
        boot(d)
        var current = Privilege.MACHINE
        val random = new Random(0x90112026L)
        for (i <- 0 until 1024) {
          val a = Inputs(valid = random.nextBoolean(), stall = i % 7 == 0,
            flush = i % 11 == 0, trap = random.nextBoolean(), mr = random.nextBoolean(),
            sr = random.nextBoolean(), delegate = random.nextBoolean(),
            mpp = random.nextInt(4), spp = random.nextBoolean(), tsr = random.nextBoolean())
          val next = candidate(s, current, a)
          val resetting = i % 97 == 0
          drive(d, a)
          d.reset.poke(resetting.B)
          d.io.NextPrivilegeModeM.expect(next.U)
          d.io.PrivilegeModeW.expect(current.U)
          d.clock.step()
          current = if (resetting) Privilege.MACHINE else if (a.valid && !a.stall &&
            !a.flush) next
            else current
          d.io.PrivilegeModeW.expect(current.U)
          d.io.NextPrivilegeModeM.expect(candidate(s, current, a).U)
          assert((if (s) Set(Privilege.USER, Privilege.SUPERVISOR,
            Privilege.MACHINE) else Set(Privilege.USER, Privilege.MACHINE))(current))
        }
      }
    }
  }

  it should "generate stable 14-port synchronous two-bit RTL in both S configurations" in {
    val in = Map("ValidM" -> 1, "StallW" -> 1, "FlushM" -> 1, "TrapM" -> 1,
      "mretM" -> 1, "sretM" -> 1, "DelegateM" -> 1, "STATUS_MPP" -> 2,
      "STATUS_SPP" -> 1, "STATUS_TSR" -> 1)
    val out = Map("NextPrivilegeModeM" -> 2, "PrivilegeModeW" -> 2)
    for (s <- Seq(false, true)) {
      val rtl = (new ChiselStage).emitVerilog(new PrivMode(s),
        Array("--target-dir", Files.createTempDirectory("priv-mode-rtl-").toString))
      val pattern = "(?m)^\\s*(input|output)\\s+(?:\\[(\\d+):0\\]\\s+)?(\\w+)".r
      val ports = pattern.findAllMatchIn(rtl).map { m =>
        m.group(3) -> (m.group(1), Option(m.group(2)).map(_.toInt + 1).getOrElse(1))
      }.toMap
      val wanted = in.map { case (n, w) => s"io_$n" -> ("input", w) } ++
        out.map { case (n, w) => s"io_$n" -> ("output", w) } ++
        Map("clk" -> ("input", 1), "reset" -> ("input", 1))
      assert(ports == wanted)
      assert(rtl.contains("always @(posedge clk)"))
      assert(!rtl.contains("posedge reset") && !rtl.contains("negedge"))
      assert(rtl.contains("2'h3"))
    }
  }
}
