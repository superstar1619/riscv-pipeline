package riscvpipeline.privileged

import chisel3._
import chisel3.stage.ChiselStage
import chisel3.util.{Cat, MuxLookup}
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.Files
import scala.language.reflectiveCalls
import scala.util.Random

private class CSRInterruptHarness(s: Boolean = true, sstc: Boolean = false)
    extends Module {
  val io = IO(new CSRInterruptIO)
  val dut = Module(new CSRInterrupt(s, sstc))
  dut.clk := clock
  dut.reset := reset.asBool
  io <> dut.io
}

// 生产模块只在测试中连接。软件读值与读改写基值明确分开并零扩展至RV32。
private class InterruptAccessHarness(s: Boolean = true, sstc: Boolean = false)
    extends Module {
  val io = IO(new Bundle {
    val Instr = Input(UInt(32.W))
    val Src = Input(UInt(32.W))
    val Mode = Input(UInt(2.W))
    val Valid = Input(Bool())
    val Stall = Input(Bool())
    val Flush = Input(Bool())
    val Deleg = Input(UInt(12.W))
    val Pins = Input(UInt(12.W))
    val STCE = Input(Bool())
    val Result = Output(UInt(32.W))
    val Modify = Output(UInt(32.W))
    val WriteValue = Output(UInt(32.W))
    val Write = Output(Bool())
    val Illegal = Output(Bool())
    val MIP = Output(UInt(12.W))
    val MIE = Output(UInt(12.W))
    val SIP = Output(UInt(12.W))
    val SIE = Output(UInt(12.W))
    val Software = Output(UInt(12.W))
  })
  val interrupts = Module(new CSRInterrupt(s, sstc))
  val access = Module(new CSRAccess)
  interrupts.clk := clock
  interrupts.reset := reset.asBool
  val Address = access.io.CSRAdrM
  val Read = MuxLookup(Address, 0.U(12.W))(Seq(
    "h304".U -> interrupts.io.MIE_REGW, "h344".U -> interrupts.io.MIP_REGW,
    "h104".U -> interrupts.io.SIE_REGW, "h144".U -> interrupts.io.SIP_REGW))
  val Modify = MuxLookup(Address, 0.U(12.W))(Seq(
    "h304".U -> interrupts.io.MIE_REGW,
    "h344".U -> interrupts.io.MIP_REGW_writeable,
    "h104".U -> interrupts.io.SIE_REGW,
    "h144".U -> interrupts.io.SIP_REGW_writeable))
  access.io.InstrM := io.Instr
  access.io.SrcAM := io.Src
  access.io.ValidM := io.Valid
  access.io.PrivilegeModeW := io.Mode
  access.io.CSRReadValM := Cat(0.U(20.W), Read)
  access.io.CSRModifyValM := Cat(0.U(20.W), Modify)
  access.io.CSRExistsM := Address === "h304".U || Address === "h344".U ||
    (s.B && (Address === "h104".U || Address === "h144".U))
  access.io.CSRAccessAllowedM := s.B || io.Mode =/= 1.U(2.W)
  interrupts.io.ValidM := io.Valid
  interrupts.io.StallW := io.Stall
  interrupts.io.FlushM := io.Flush
  interrupts.io.WriteMIEM := access.io.CSRWriteM && Address === "h304".U
  interrupts.io.WriteMIPM := access.io.CSRWriteM && Address === "h344".U
  interrupts.io.WriteSIEM := access.io.CSRWriteM && Address === "h104".U
  interrupts.io.WriteSIPM := access.io.CSRWriteM && Address === "h144".U
  interrupts.io.CSRWriteValM := access.io.CSRWriteValM
  interrupts.io.MIDELEG_REGW := io.Deleg
  interrupts.io.MExtInt := io.Pins(11)
  interrupts.io.SExtInt := io.Pins(9)
  interrupts.io.MTimerInt := io.Pins(7)
  interrupts.io.STimerInt := io.Pins(5)
  interrupts.io.MSwInt := io.Pins(3)
  interrupts.io.ENVCFG_STCE := io.STCE
  io.Result := access.io.CSRResultM
  io.Modify := access.io.CSRModifyValM
  io.WriteValue := access.io.CSRWriteValM
  io.Write := access.io.CSRWriteM
  io.Illegal := access.io.IllegalCSRAccessM
  io.MIP := interrupts.io.MIP_REGW
  io.MIE := interrupts.io.MIE_REGW
  io.SIP := interrupts.io.SIP_REGW
  io.SIE := interrupts.io.SIE_REGW
  io.Software := interrupts.io.MIP_REGW_writeable
}

class CSRInterruptSpec extends AnyFlatSpec with ChiselScalatestTester {
  private case class Inputs(
    word: BigInt = 0, mie: Boolean = false, mip: Boolean = false,
    sie: Boolean = false, sip: Boolean = false, deleg: Int = 0, pins: Int = 0,
    stce: Boolean = false, valid: Boolean = true,
    stall: Boolean = false, flush: Boolean = false
  )
  private def drive(d: CSRInterruptHarness, a: Inputs): Unit = {
    d.io.CSRWriteValM.poke(a.word.U)
    d.io.WriteMIEM.poke(a.mie.B)
    d.io.WriteMIPM.poke(a.mip.B)
    d.io.WriteSIEM.poke(a.sie.B)
    d.io.WriteSIPM.poke(a.sip.B)
    d.io.MIDELEG_REGW.poke(a.deleg.U)
    d.io.MExtInt.poke(((a.pins & 0x800) != 0).B)
    d.io.SExtInt.poke(((a.pins & 0x200) != 0).B)
    d.io.MTimerInt.poke(((a.pins & 0x080) != 0).B)
    d.io.STimerInt.poke(((a.pins & 0x020) != 0).B)
    d.io.MSwInt.poke(((a.pins & 0x008) != 0).B)
    d.io.ENVCFG_STCE.poke(a.stce.B)
    d.io.ValidM.poke(a.valid.B)
    d.io.StallW.poke(a.stall.B)
    d.io.FlushM.poke(a.flush.B)
  }
  private def boot(d: CSRInterruptHarness): Unit = {
    drive(d, Inputs(valid = false))
    d.reset.poke(true.B)
    d.clock.step()
    d.reset.poke(false.B)
    check(d)
  }
  private def cycle(d: CSRInterruptHarness, a: Inputs): Unit = {
    drive(d, a)
    d.clock.step()
  }
  private def check(d: CSRInterruptHarness, mip: Int = 0, mie: Int = 0,
                    sw: Int = 0, sip: Int = 0, sie: Int = 0, ssw: Int = 0): Unit = {
    d.io.MIP_REGW.expect(mip.U)
    d.io.MIE_REGW.expect(mie.U)
    d.io.MIP_REGW_writeable.expect(sw.U)
    d.io.SIP_REGW.expect(sip.U)
    d.io.SIE_REGW.expect(sie.U)
    d.io.SIP_REGW_writeable.expect(ssw.U)
  }

  // 独立逐中断字段模型；不用整字RTL写掩码表达式计算期望。
  private class Model(s: Boolean, sstc: Boolean) {
    private val supervisor = Set(1, 5, 9)
    private val enabled = Array.fill(12)(false)
    private val software = Array.fill(12)(false)
    private def bit(word: BigInt, index: Int): Boolean = word.testBit(index)
    private def delegated(a: Inputs, index: Int): Boolean =
      s && supervisor(index) && bit(a.deleg, index)
    private def pack(bits: Seq[Int]): Int = bits.map(1 << _).sum
    private def pending(a: Inputs, index: Int): Boolean = index match {
      case 11 | 7 | 3 => bit(a.pins, index)
      case 9 => s && (software(9) || bit(a.pins, 9))
      case 5 => s && (if (sstc && a.stce) bit(a.pins, 5) else software(5))
      case 1 => s && software(1)
      case _ => false
    }
    def expect(d: CSRInterruptHarness, a: Inputs): Unit = {
      val indices = 0 until 12
      check(d, pack(indices.filter(pending(a, _))), pack(indices.filter(enabled(_))),
        pack(indices.filter(software(_))),
        pack(indices.filter(i => delegated(a, i) && pending(a, i))),
        pack(indices.filter(i => delegated(a, i) && enabled(i))),
        pack(indices.filter(i => delegated(a, i) && software(i))))
    }
    def update(a: Inputs, resetting: Boolean): Unit = {
      if (resetting) {
        java.util.Arrays.fill(enabled, false)
        java.util.Arrays.fill(software, false)
      } else if (a.valid && !a.stall && !a.flush) {
        if (a.mie) {
          for (i <- 0 until 12) enabled(i) =
            (Set(3, 7, 11)(i) || (s && supervisor(i))) && bit(a.word, i)
        } else if (a.sie) {
          for (i <- supervisor if delegated(a, i)) enabled(i) = bit(a.word, i)
        }
        if (a.mip) {
          for (i <- 0 until 12) software(i) = s && supervisor(i) &&
            !(i == 5 && sstc && a.stce) && bit(a.word, i)
        } else if (a.sip && delegated(a, 1)) software(1) = bit(a.word, 1)
      }
    }
  }

  behavior of "CSRInterrupt"

  it should "reset software state synchronously without hiding asserted hardware pins" in {
    test(new CSRInterruptHarness) { d =>
      boot(d)
      cycle(d, Inputs(word = 0xffffffffL, mie = true, mip = true))
      check(d, 0x222, 0xaaa, 0x222)
      drive(d, Inputs(word = 0xffffffffL, mie = true, mip = true, pins = 0xa88,
        deleg = 0x222, valid = false, stall = true, flush = true))
      d.reset.poke(true.B)
      check(d, 0xaaa, 0xaaa, 0x222, 0x222, 0x222, 0x222)
      d.clock.step()
      check(d, 0xa88, 0, 0, 0x200)
      d.reset.poke(false.B)
      drive(d, Inputs())
      check(d)
    }
  }

  it should "apply literal RV32 masks for all legal configurations and STCE values" in {
    val rows = Seq((false, false, false, 0x888, 0x000),
      (false, false, true, 0x888, 0x000), (true, false, false, 0xaaa, 0x222),
      (true, false, true, 0xaaa, 0x222), (true, true, false, 0xaaa, 0x222),
      (true, true, true, 0xaaa, 0x202))
    for ((s, sstc, stce, enable, pending) <- rows) {
      test(new CSRInterruptHarness(s, sstc)) { d =>
        boot(d)
        cycle(d, Inputs(word = 0xffffffffL, mie = true, mip = true,
          deleg = 0xfff, stce = stce))
        val sEnable = if (s) 0x222 else 0
        check(d, pending, enable, pending, pending, sEnable, pending)
        cycle(d, Inputs(word = BigInt("fffff000", 16), mie = true, mip = true,
          deleg = 0xfff, stce = stce))
        check(d)
      }
    }
  }

  it should "map hardware inputs to pending bits independently of CSR update gates" in {
    for ((s, sstc) <- Seq((false, false), (true, false), (true, true))) {
      test(new CSRInterruptHarness(s, sstc)) { d =>
        boot(d)
        val rows = Seq((0x800, 0x800), (0x080, 0x080), (0x008, 0x008),
          (0x200, if (s) 0x200 else 0), (0x020, if (sstc) 0x020 else 0))
        for ((pins, pending) <- rows) {
          drive(d, Inputs(pins = pins, stce = true, valid = false,
            stall = true, flush = true))
          check(d, mip = pending)
          drive(d, Inputs())
          check(d)
        }
      }
    }
  }

  it should "OR external SEIP with only the software SEIP bit and never capture pins" in {
    test(new CSRInterruptHarness) { d =>
      boot(d)
      for ((word, pins, want) <- Seq((0x000, 0x000, 0x000),
           (0x000, 0x200, 0x200), (0x200, 0x000, 0x200), (0x200, 0x200, 0x200))) {
        cycle(d, Inputs(word = word, mip = true, pins = pins, deleg = 0x200))
        check(d, mip = want, sw = word, sip = want, ssw = word)
      }
      cycle(d, Inputs(word = 0, mip = true, pins = 0xa88))
      check(d, mip = 0xa88)
      drive(d, Inputs())
      check(d)
    }
  }

  it should "restrict supervisor read views to delegation without changing hidden state" in {
    test(new CSRInterruptHarness) { d =>
      boot(d)
      cycle(d, Inputs(word = 0xaaa, mie = true, mip = true, pins = 0x888))
      for ((deleg, visible) <- Seq((0x000, 0x000), (0x002, 0x002), (0x020, 0x020),
           (0x200, 0x200), (0x222, 0x222), (0x888, 0x000), (0xfff, 0x222))) {
        drive(d, Inputs(deleg = deleg, pins = 0x888))
        check(d, 0xaaa, 0xaaa, 0x222, visible, visible, visible)
        d.clock.step()
        check(d, 0xaaa, 0xaaa, 0x222, visible, visible, visible)
      }
    }
  }

  it should "merge SIE writes only into delegated supervisor enable fields" in {
    test(new CSRInterruptHarness) { d =>
      boot(d)
      cycle(d, Inputs(word = 0xaaa, mie = true))
      cycle(d, Inputs(word = 0, sie = true, deleg = 0x020))
      check(d, mie = 0xa8a)
      cycle(d, Inputs(word = 0, sie = true, deleg = 0x888))
      check(d, mie = 0xa8a)
      cycle(d, Inputs(word = 0, sie = true, deleg = 0xfff))
      check(d, mie = 0x888)
      cycle(d, Inputs(word = 0xffffffffL, sie = true, deleg = 0x020))
      check(d, mie = 0x8a8, sie = 0x020)
      drive(d, Inputs(deleg = 0x222))
      check(d, mie = 0x8a8, sie = 0x020)
      cycle(d, Inputs(word = 0, mie = true))
      check(d)
    }
  }

  it should "write only delegated SSIP through SIP while preserving other software fields" in {
    test(new CSRInterruptHarness) { d =>
      boot(d)
      cycle(d, Inputs(word = 0x222, mip = true))
      cycle(d, Inputs(word = 0, sip = true, deleg = 0x220))
      check(d, mip = 0x222, sw = 0x222, sip = 0x220, ssw = 0x220)
      cycle(d, Inputs(word = 0, sip = true, deleg = 0xfff))
      check(d, mip = 0x220, sw = 0x220, sip = 0x220, ssw = 0x220)
      cycle(d, Inputs(word = 0xffffffffL, sip = true, deleg = 0xfff))
      check(d, mip = 0x222, sw = 0x222, sip = 0x222, ssw = 0x222)
      cycle(d, Inputs(word = 0, mip = true, pins = 0x888, deleg = 0x222))
      check(d, mip = 0x888)
    }
  }

  it should "hold software state under invalid stall and flush combinations" in {
    test(new CSRInterruptHarness) { d =>
      boot(d)
      cycle(d, Inputs(word = 0xffffffffL, mie = true, mip = true))
      for (valid <- Seq(false, true); stall <- Seq(false, true);
           flush <- Seq(false, true) if !valid || stall || flush) {
        cycle(d, Inputs(word = 0, mie = true, mip = true, sie = true, sip = true,
          deleg = 0x222, pins = 0x888, valid = valid, stall = stall, flush = flush))
        check(d, 0xaaa, 0xaaa, 0x222, 0x222, 0x222, 0x222)
      }
      cycle(d, Inputs(word = 0, mie = true, mip = true))
      check(d)
    }
  }

  it should "prioritize machine writes per state bank while allowing both banks to update" in {
    test(new CSRInterruptHarness) { d =>
      boot(d)
      cycle(d, Inputs(word = 0xaaa, mie = true, mip = true))
      cycle(d, Inputs(word = 0, mie = true, mip = true, sie = true, sip = true,
        deleg = 0))
      check(d)
      cycle(d, Inputs(word = 0xaaa, mie = true, sip = true, deleg = 0x222))
      check(d, 0x002, 0xaaa, 0x002, 0x002, 0x222, 0x002)
      cycle(d, Inputs(word = 0x220, mip = true, sie = true, deleg = 0x222))
      check(d, 0x220, 0xaa8, 0x220, 0x220, 0x220, 0x220)
    }
  }

  it should "select Sstc STIP with a dynamic write mask and independent other bits" in {
    test(new CSRInterruptHarness(true, true)) { d =>
      boot(d)
      cycle(d, Inputs(word = 0x222, mip = true, deleg = 0x222))
      check(d, 0x222, 0, 0x222, 0x222, 0, 0x222)
      drive(d, Inputs(stce = true, pins = 0x080, deleg = 0x222))
      check(d, 0x282, 0, 0x222, 0x202, 0, 0x222)
      drive(d, Inputs(stce = true, pins = 0x0a0, deleg = 0x222))
      check(d, 0x2a2, 0, 0x222, 0x222, 0, 0x222)
      cycle(d, Inputs(word = 0, sip = true, stce = true, deleg = 0x222))
      check(d, 0x200, 0, 0x220, 0x200, 0, 0x220)
      drive(d, Inputs())
      check(d, 0x220, 0, 0x220)
      cycle(d, Inputs(word = 0xffffffffL, mip = true, stce = true, pins = 0x020))
      check(d, 0x222, 0, 0x202)
      drive(d, Inputs())
      check(d, 0x202, 0, 0x202)
    }
  }

  private def csr(address: Int, function: Int, source: Int = 1, rd: Int = 1): BigInt =
    (BigInt(address) << 20) | (BigInt(source) << 15) | (BigInt(function) << 12) |
      (BigInt(rd) << 7) | 0x73
  private def accessDrive(d: InterruptAccessHarness, instr: BigInt, src: BigInt = 0,
                          mode: Int = 3, pins: Int = 0, deleg: Int = 0x222,
                          valid: Boolean = true, stall: Boolean = false,
                          flush: Boolean = false, stce: Boolean = false): Unit = {
    d.io.Instr.poke(instr.U)
    d.io.Src.poke(src.U)
    d.io.Mode.poke(mode.U)
    d.io.Pins.poke(pins.U)
    d.io.Deleg.poke(deleg.U)
    d.io.Valid.poke(valid.B)
    d.io.Stall.poke(stall.B)
    d.io.Flush.poke(flush.B)
    d.io.STCE.poke(stce.B)
  }
  private def accessBoot(d: InterruptAccessHarness): Unit = {
    accessDrive(d, 0, valid = false)
    d.reset.poke(true.B)
    d.clock.step()
    d.reset.poke(false.B)
  }

  it should "return external SEIP while CSRRS and CSRRC modify only software bits" in {
    test(new InterruptAccessHarness) { d =>
      accessBoot(d)
      // CSRRS写SSIP，同时SExtInt高；rd读到SEIP，但不能把它粘在软件状态。
      accessDrive(d, csr(0x344, 2), src = 0x002, pins = 0x200)
      d.io.Result.expect(0x200.U)
      d.io.Modify.expect(0.U)
      d.io.WriteValue.expect(0x002.U)
      d.io.Illegal.expect(false.B)
      d.clock.step()
      d.io.Software.expect(0x002.U)
      d.io.MIP.expect(0x202.U)
      accessDrive(d, csr(0x344, 2, source = 0), pins = 0)
      d.io.Result.expect(0x002.U)
      d.io.Write.expect(false.B)
      // 非零rs1持有零仍可发起写；硬件SEIP不参加这个零掩码修改。
      accessDrive(d, csr(0x344, 2), src = 0, pins = 0x200)
      d.io.Write.expect(true.B)
      d.io.WriteValue.expect(0x002.U)
      d.clock.step()
      d.io.Software.expect(0x002.U)
      accessDrive(d, csr(0x344, 1), src = 0x220, pins = 0x200)
      d.io.Result.expect(0x202.U)
      d.clock.step()
      accessDrive(d, csr(0x344, 3), src = 0x200, pins = 0x200)
      d.io.Result.expect(0x220.U)
      d.io.Modify.expect(0x220.U)
      d.io.WriteValue.expect(0x020.U)
      d.clock.step()
      d.io.Software.expect(0x020.U)
      d.io.MIP.expect(0x220.U)
      accessDrive(d, csr(0x344, 2, source = 0))
      d.io.Result.expect(0x020.U)
      d.io.MIP.expect(0x020.U)
    }
  }

  it should "use real CSR access permissions shared S views and submission gates" in {
    test(new InterruptAccessHarness) { d =>
      accessBoot(d)
      accessDrive(d, csr(0x304, 1), src = 0xaaa)
      d.clock.step()
      accessDrive(d, csr(0x104, 3), src = 0x020, mode = 1, deleg = 0x020)
      d.io.Result.expect(0x020.U)
      d.clock.step()
      d.io.MIE.expect(0xa8a.U)
      accessDrive(d, csr(0x344, 1), src = 0x222)
      d.clock.step()
      accessDrive(d, csr(0x144, 7, source = 2), mode = 1, pins = 0x200)
      d.io.Result.expect(0x222.U)
      d.io.WriteValue.expect(0x220.U)
      d.clock.step()
      d.io.Software.expect(0x220.U)
      for ((address, mode) <- Seq((0x304, 1), (0x344, 0), (0x104, 0),
           (0x144, 0), (0x304, 2), (0x305, 3))) {
        accessDrive(d, csr(address, 1), src = 0, mode = mode)
        d.io.Illegal.expect(true.B)
        d.io.Write.expect(false.B)
        d.io.Result.expect(0.U)
        d.clock.step()
        d.io.MIE.expect(0xa8a.U)
        d.io.Software.expect(0x220.U)
      }
      for ((valid, stall, flush) <- Seq((false, false, false),
           (true, true, false), (true, false, true))) {
        accessDrive(d, csr(0x344, 1), src = 0, valid = valid,
          stall = stall, flush = flush, pins = 0x080)
        d.clock.step()
        d.io.Software.expect(0x220.U)
        d.io.MIP.expect(0x2a0.U)
      }
    }
    test(new InterruptAccessHarness(false)) { d =>
      accessBoot(d)
      for (address <- Seq(0x104, 0x144)) {
        accessDrive(d, csr(address, 1), src = 0xffffffffL)
        d.io.Illegal.expect(true.B)
        d.clock.step()
        d.io.MIE.expect(0.U)
        d.io.Software.expect(0.U)
      }
    }
  }

  it should "ignore all S inputs and writes when supervisor support is disabled" in {
    test(new CSRInterruptHarness(false)) { d =>
      boot(d)
      cycle(d, Inputs(word = 0xffffffffL, mie = true, mip = true, deleg = 0xfff,
        pins = 0xaa8, stce = true))
      check(d, mip = 0x888, mie = 0x888)
      cycle(d, Inputs(word = 0, sie = true, sip = true, deleg = 0xfff,
        pins = 0x220, stce = true))
      check(d, mie = 0x888)
    }
  }

  it should "match a per-field model across seeded state and input sequences" in {
    for ((s, sstc) <- Seq((false, false), (true, false), (true, true))) {
      test(new CSRInterruptHarness(s, sstc)) { d =>
        boot(d)
        val model = new Model(s, sstc)
        val random = new Random(0x8c512026L)
        for (_ <- 0 until 1024) {
          val a = Inputs(word = BigInt(32, random), mie = random.nextBoolean(),
            mip = random.nextBoolean(), sie = random.nextBoolean(),
            sip = random.nextBoolean(), deleg = random.nextInt(4096),
            pins = random.nextInt(4096), stce = random.nextBoolean(),
            valid = random.nextInt(5) != 0, stall = random.nextInt(5) == 0,
            flush = random.nextInt(5) == 0)
          val resetting = random.nextInt(64) == 0
          drive(d, a)
          d.reset.poke(resetting.B)
          model.expect(d, a)
          d.clock.step()
          model.update(a, resetting)
          model.expect(d, a)
        }
      }
    }
  }

  it should "generate stable ports and synchronous reset RTL in every legal configuration" in {
    val ports = Map("clk" -> 1, "reset" -> 1, "io_ValidM" -> 1,
      "io_StallW" -> 1, "io_FlushM" -> 1, "io_WriteMIEM" -> 1,
      "io_WriteMIPM" -> 1, "io_WriteSIEM" -> 1, "io_WriteSIPM" -> 1,
      "io_CSRWriteValM" -> 32, "io_MIDELEG_REGW" -> 12,
      "io_MExtInt" -> 1, "io_SExtInt" -> 1, "io_MTimerInt" -> 1,
      "io_STimerInt" -> 1, "io_MSwInt" -> 1, "io_ENVCFG_STCE" -> 1,
      "io_MIP_REGW" -> 12, "io_MIE_REGW" -> 12, "io_SIP_REGW" -> 12,
      "io_SIE_REGW" -> 12, "io_MIP_REGW_writeable" -> 12,
      "io_SIP_REGW_writeable" -> 12)
    for ((s, sstc) <- Seq((false, false), (true, false), (true, true))) {
      val dir = Files.createTempDirectory("csr-interrupt-rtl-").toString
      val rtl = (new ChiselStage).emitVerilog(new CSRInterrupt(s, sstc),
        Array("--target-dir", dir))
      val portPattern = "(?m)^\\s*(input|output)\\s+(?:\\[(\\d+):0\\]\\s+)?(\\w+)".r
      val actual = portPattern.findAllMatchIn(rtl).map { m =>
        m.group(3) -> (m.group(1), Option(m.group(2)).map(_.toInt + 1).getOrElse(1))
      }.toMap
      val outputs = Set("io_MIP_REGW", "io_MIE_REGW", "io_SIP_REGW", "io_SIE_REGW",
        "io_MIP_REGW_writeable", "io_SIP_REGW_writeable")
      val wanted = ports.map { case (name, width) =>
        name -> (if (outputs(name)) "output" else "input", width)
      }
      assert(actual == wanted)
      assert(rtl.contains("always @(posedge clk)"))
      assert(!rtl.contains("posedge reset") && !rtl.contains("negedge"))
      assert(rtl.contains("if (reset)"))
    }
  }

  it should "reject Sstc without supervisor support during elaboration" in {
    intercept[IllegalArgumentException] {
      (new ChiselStage).emitVerilog(new CSRInterrupt(false, true),
        Array("--target-dir", Files.createTempDirectory("bad-csr-interrupt-").toString))
    }
  }
}
