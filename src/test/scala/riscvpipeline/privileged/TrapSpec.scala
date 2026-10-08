package riscvpipeline.privileged

import chisel3._
import chisel3.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.Files
import scala.util.Random

private class TrapHarness(supportedS: Boolean) extends Module {
  val io = IO(new TrapIO)
  private val dut = Module(new Trap(supportedS))
  io <> dut.io
}

class TrapSpec extends AnyFlatSpec with ChiselScalatestTester {
  private val interrupts = Seq(11, 3, 7, 9, 1, 5)
  // 表顺序来自教材表8.4，ECALL由当前模式产生8/9/11。
  private val faults = Seq(12, 1, 2, 0, 3, 8, 6, 4, 15, 13, 7, 5)
  private def bits(values: Seq[Int]): Int = values.foldLeft(0)((m, n) => m | (1 << n))
  private case class Input(
    mode: Int = 3, valid: Boolean = true, reset: Boolean = false,
    mip: Int = 0, mie: Int = 0xfff, mideleg: Int = 0, medeleg: Int = 0,
    mGlobal: Boolean = true, sGlobal: Boolean = true, committedM: Boolean = false,
    committedF: Boolean = false, wfiM: Boolean = false, wfiW: Boolean = false,
    exceptions: Set[Int] = Set.empty
  )
  private case class Expected(
    trap: Boolean = false, interrupt: Boolean = false, exception: Boolean = false,
    pending: Boolean = false, delegate: Boolean = false, cause: Int = 0
  )

  private def check(dut: TrapHarness, a: Input, e: Expected): Unit = {
    withClue(s"input=$a expected=$e: ") {
      dut.io.reset.poke(a.reset.B)
      dut.io.ValidM.poke(a.valid.B)
      dut.io.CommittedM.poke(a.committedM.B)
      dut.io.CommittedF.poke(a.committedF.B)
      dut.io.wfiM.poke(a.wfiM.B)
      dut.io.wfiW.poke(a.wfiW.B)
      dut.io.STATUS_MIE.poke(a.mGlobal.B)
      dut.io.STATUS_SIE.poke(a.sGlobal.B)
      dut.io.PrivilegeModeW.poke(a.mode.U)
      dut.io.MIP_REGW.poke(a.mip.U)
      dut.io.MIE_REGW.poke(a.mie.U)
      dut.io.MIDELEG_REGW.poke(a.mideleg.U)
      dut.io.MEDELEG_REGW.poke(a.medeleg.U)
      val ports = Seq(dut.io.InstrPageFaultM, dut.io.InstrAccessFaultM,
        dut.io.IllegalInstrFaultM, dut.io.InstrMisalignedFaultM,
        dut.io.BreakpointFaultM, dut.io.EcallFaultM, dut.io.StoreAmoMisalignedFaultM,
        dut.io.LoadMisalignedFaultM, dut.io.StoreAmoPageFaultM, dut.io.LoadPageFaultM,
        dut.io.StoreAmoAccessFaultM, dut.io.LoadAccessFaultM)
      ports.zip(faults).foreach { case (port, cause) =>
        port.poke(a.exceptions.contains(cause).B)
      }
      dut.io.TrapM.expect(e.trap.B)
      dut.io.InterruptM.expect(e.interrupt.B)
      dut.io.ExceptionM.expect(e.exception.B)
      dut.io.IntPendingM.expect(e.pending.B)
      dut.io.DelegateM.expect(e.delegate.B)
      dut.io.CauseM.expect(e.cause.U)
    }
  }

  // 软件以事件候选的目标模式、表中序号排序，独立于硬件连接与优先Mux。
  private def reference(a: Input, supportedS: Boolean): Expected = {
    val supported = Set(0, 3).contains(a.mode) || (supportedS && a.mode == 1)
    val allowed = if (supportedS) interrupts else Seq(11, 3, 7)
    val local = allowed.filter(n => (a.mip & a.mie & (1 << n)) != 0)
    val pending = local.nonEmpty && !a.reset
    if (a.reset || !a.valid || !supported || a.committedF) {
      return Expected(pending = pending)
    }
    val candidates = local.filter { n =>
      val delegated = supportedS && (a.mideleg & (1 << n)) != 0
      if (delegated) a.mode == 0 || (a.mode == 1 && a.sGlobal)
      else a.mode != 3 || a.mGlobal
    }.sortBy { n =>
      val toS = supportedS && (a.mideleg & (1 << n)) != 0
      (if (toS) 1 else 0, interrupts.indexOf(n))
    }
    val irq = if (a.committedM || (a.wfiM && !a.wfiW)) None else candidates.headOption
    val fault = faults.find(a.exceptions.contains).map { n =>
      if (n == 8) 8 + a.mode else n
    }
    val chosen = irq.orElse(fault)
    val delegated = chosen.exists { n =>
      val delegation = if (irq.nonEmpty) a.mideleg else a.medeleg
      supportedS && a.mode != 3 && (delegation & (1 << n)) != 0
    }
    Expected(chosen.nonEmpty, irq.nonEmpty, fault.nonEmpty, pending,
      delegated, chosen.getOrElse(0))
  }

  behavior of "Trap"

  it should "report every exception and mode-specific ECALL with zero idle cause" in {
    for (s <- Seq(false, true)) {
      test(new TrapHarness(s)) { dut =>
        check(dut, Input(), Expected())
        for (mode <- (if (s) Seq(0, 1, 3) else Seq(0, 3)); fault <- faults) {
          val cause = if (fault == 8) 8 + mode else fault
          check(dut, Input(mode = mode, exceptions = Set(fault)),
            Expected(trap = true, exception = true, cause = cause))
        }
      }
    }
  }

  it should "select all exception pairs in the architectural priority order" in {
    test(new TrapHarness(true)) { dut =>
      for (mode <- Seq(0, 1, 3); i <- faults.indices; j <- i until faults.size) {
        val cause = if (faults(i) == 8) 8 + mode else faults(i)
        check(dut, Input(mode = mode, exceptions = Set(faults(i), faults(j))),
          Expected(trap = true, exception = true, cause = cause))
      }
      check(dut, Input(exceptions = faults.toSet),
        Expected(trap = true, exception = true, cause = 12))
    }
  }

  it should "apply local enable masks and keep pending independent of execution gates" in {
    for (s <- Seq(false, true)) {
      test(new TrapHarness(s)) { dut =>
        for (bit <- 0 until 12; enabled <- Seq(false, true)) {
          val present = enabled && (if (s) interrupts else Seq(11, 3, 7)).contains(bit)
          val a = Input(mip = 1 << bit, mie = if (enabled) 1 << bit else 0)
          check(dut, a, Expected(trap = present, interrupt = present,
            pending = present, cause = if (present) bit else 0))
        }
        for (mode <- 0 until 4; valid <- Seq(false, true);
             committedM <- Seq(false, true); committedF <- Seq(false, true)) {
          val a = Input(mode = mode, valid = valid, mip = 1 << 7, mGlobal = false,
            sGlobal = false, committedM = committedM, committedF = committedF,
            wfiM = true)
          check(dut, a, Expected(pending = true))
        }
        check(dut, Input(reset = true, mip = 0xfff, exceptions = faults.toSet), Expected())
      }
    }
  }

  it should "enforce U S M global enables and delegation for each interrupt" in {
    for (s <- Seq(false, true)) {
      test(new TrapHarness(s)) { dut =>
        for (bit <- interrupts; mode <- 0 until 4; m <- Seq(false, true);
             sup <- Seq(false, true); delegated <- Seq(false, true)) {
          val a = Input(mode = mode, mip = 1 << bit, mGlobal = m, sGlobal = sup,
            mideleg = if (delegated) 1 << bit else 0)
          check(dut, a, reference(a, s))
        }
      }
    }
  }

  it should "prioritize M target interrupts then S target interrupts before exceptions" in {
    test(new TrapHarness(true)) { dut =>
      for (mask <- 1 until 64; delegation <- Seq(0, 0x222, 0xaaa, 0x888, 0x800)) {
        val sources = interrupts.zipWithIndex.collect {
          case (cause, index) if (mask & (1 << index)) != 0 => cause
        }
        val a = Input(mode = 0, mip = bits(sources), mideleg = delegation,
          medeleg = 0xffff, exceptions = faults.toSet)
        val mTargets = sources.filter(n => (delegation & (1 << n)) == 0)
        val chosen = mTargets.headOption.getOrElse(sources.head)
        check(dut, a, Expected(trap = true, interrupt = true, exception = true,
          pending = true, delegate = mTargets.isEmpty, cause = chosen))
      }
    }
  }

  it should "delay interrupts at commit and WFI boundaries while preserving exceptions" in {
    test(new TrapHarness(true)) { dut =>
      for (cm <- Seq(false, true); cf <- Seq(false, true); wm <- Seq(false, true);
           ww <- Seq(false, true); fault <- Seq(false, true)) {
        val irq = !cm && !cf && (!wm || ww)
        val exc = fault && !cf
        val a = Input(mip = 1 << 3, committedM = cm, committedF = cf,
          wfiM = wm, wfiW = ww, exceptions = if (fault) Set(2) else Set.empty)
        check(dut, a, Expected(trap = irq || exc, interrupt = irq, exception = exc,
          pending = true, cause = if (irq) 3 else if (exc) 2 else 0))
      }
    }
  }

  it should "delegate only actual traps from U or S and never downgrade M mode" in {
    for (s <- Seq(false, true)) {
      test(new TrapHarness(s)) { dut =>
        for (mode <- 0 until 4; fault <- faults; delegated <- Seq(false, true)) {
          val a = Input(mode = mode, medeleg = if (delegated) 0xffff else 0,
            exceptions = Set(fault))
          check(dut, a, reference(a, s))
        }
        for (mode <- 0 until 4; reset <- Seq(false, true);
             valid <- Seq(false, true); committed <- Seq(false, true)) {
          check(dut, Input(mode = mode, reset = reset, valid = valid,
            committedF = committed, medeleg = 0xffff, mideleg = 0xfff), Expected())
        }
      }
    }
  }

  it should "match a fixed seed event reference across both configurations" in {
    for (s <- Seq(false, true)) {
      test(new TrapHarness(s)) { dut =>
        val random = new Random(0x8a92026L)
        for (_ <- 0 until 512) {
          val a = Input(mode = random.nextInt(4), valid = random.nextInt(5) != 0,
            reset = random.nextInt(11) == 0, mip = random.nextInt(4096),
            mie = random.nextInt(4096), mideleg = random.nextInt(4096),
            medeleg = random.nextInt(65536), mGlobal = random.nextBoolean(),
            sGlobal = random.nextBoolean(), committedM = random.nextBoolean(),
            committedF = random.nextBoolean(), wfiM = random.nextBoolean(),
            wfiW = random.nextBoolean(),
            exceptions = faults.filter(_ => random.nextInt(5) == 0).toSet)
          check(dut, a, reference(a, s))
        }
      }
    }
  }

  it should "emit the exact clockless interface without state in both configurations" in {
    val boolInputs = Seq("reset", "ValidM", "CommittedM", "CommittedF", "wfiM", "wfiW",
      "STATUS_MIE", "STATUS_SIE", "InstrMisalignedFaultM", "InstrAccessFaultM",
      "InstrPageFaultM", "IllegalInstrFaultM", "BreakpointFaultM", "EcallFaultM",
      "LoadMisalignedFaultM", "StoreAmoMisalignedFaultM", "LoadAccessFaultM",
      "StoreAmoAccessFaultM", "LoadPageFaultM", "StoreAmoPageFaultM")
    val uintInputs = Map("PrivilegeModeW" -> 2, "MIP_REGW" -> 12,
      "MIE_REGW" -> 12, "MIDELEG_REGW" -> 12, "MEDELEG_REGW" -> 16)
    val boolOutputs = Seq("TrapM", "InterruptM", "ExceptionM", "IntPendingM", "DelegateM")
    val inputs = boolInputs.map(_ -> 1).toMap ++ uintInputs
    val outputs = boolOutputs.map(_ -> 1).toMap + ("CauseM" -> 5)
    for (s <- Seq(false, true)) {
      val dir = Files.createTempDirectory("trap-interface-")
      val rtl = (new ChiselStage).emitVerilog(new Trap(s),
        Array("--target-dir", dir.toString))
      val pattern = "(?m)^\\s*(input|output)\\s+(?:\\[(\\d+):0\\]\\s+)?(\\w+)".r
      val ports = pattern.findAllMatchIn(rtl).map { p =>
        p.group(3) -> (p.group(1), Option(p.group(2)).map(_.toInt + 1).getOrElse(1))
      }.toMap
      assert(ports == inputs.map { case (n, w) => s"io_$n" -> ("input", w) } ++
        outputs.map { case (n, w) => s"io_$n" -> ("output", w) })
      assert(!rtl.contains("always @"))
      assert(!rtl.contains("reg "))
    }
  }
}
