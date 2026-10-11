package riscvpipeline.privileged

import riscvpipeline.TestEncoding.{CSR, CSRFunction, Opcode, Privilege}
import chisel3._
import chisel3.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.Files

private class CSRHarness(config: PrivilegedConfig = PrivilegedConfig()) extends Module {
  val io = IO(new CSRIO)
  val dut = Module(new CSR(config))
  dut.clk := clock
  dut.reset := reset.asBool
  io <> dut.io
}

class CSRSpec extends AnyFlatSpec with ChiselScalatestTester {
  private val wordMask = BigInt("ffffffff", 16)
  private def hex(s: String): BigInt = BigInt(s, 16)
  private def csr(adr: Int, funct: Int, rs: Int = 1, rd: Int = 1): BigInt =
    (BigInt(adr) << 20) | (BigInt(rs) << 15) | (BigInt(funct) << 12) |
      (BigInt(rd) << 7) | Opcode.SYSTEM

  private def idle(d: CSRHarness): Unit = {
    d.io.InstrM.poke(0.U)
    d.io.PCM.poke(0x804.U)
    d.io.IEUAdrM.poke(0xcafe.U)
    d.io.SrcAM.poke(0.U)
    d.io.PrivilegeModeW.poke(Privilege.MACHINE.U)
    d.io.NextPrivilegeModeM.poke(Privilege.MACHINE.U)
    d.io.CauseM.poke(0.U)
    d.io.HPMEventsM.poke(0.U)
    d.io.TIME_REGW.poke(0.U)
    d.io.ValidM.poke(true.B)
    d.io.StallW.poke(false.B)
    d.io.FlushM.poke(false.B)
    d.io.TrapM.poke(false.B)
    d.io.InterruptM.poke(false.B)
    d.io.mretM.poke(false.B)
    d.io.sretM.poke(false.B)
    d.io.InstrRetiredM.poke(false.B)
    d.io.MExtInt.poke(false.B)
    d.io.SExtInt.poke(false.B)
    d.io.MTimerInt.poke(false.B)
    d.io.MSwInt.poke(false.B)
  }
  private def boot(d: CSRHarness): Unit = {
    idle(d)
    d.reset.poke(true.B)
    d.clock.step()
    d.reset.poke(false.B)
  }
  private def transaction(d: CSRHarness, adr: Int, funct: Int, src: BigInt = 0,
      rs: Int = 1, rd: Int = 1, mode: Int = Privilege.MACHINE): Unit = {
    idle(d)
    d.io.InstrM.poke(csr(adr, funct, rs, rd).U)
    d.io.SrcAM.poke(src.U)
    d.io.PrivilegeModeW.poke(mode.U)
  }
  private def write(d: CSRHarness, adr: Int, value: BigInt,
    mode: Int = Privilege.MACHINE): Unit = {
    transaction(d, adr, CSRFunction.RW, value, mode = mode)
    d.io.IllegalCSRAccessM.expect(false.B)
    d.io.CSRWriteM.expect(true.B)
    d.clock.step()
  }
  private def read(d: CSRHarness, adr: Int, value: BigInt,
    mode: Int = Privilege.MACHINE): Unit = {
    transaction(d, adr, CSRFunction.RS, rs = 0, mode = mode)
    d.io.IllegalCSRAccessM.expect(false.B)
    d.io.CSRReadM.expect(true.B)
    d.io.CSRWriteM.expect(false.B)
    d.io.CSRResultM.expect(value.U)
  }

  behavior of "CSR"

  it should "execute six instructions across machine supervisor and counter groups" in {
    test(new CSRHarness) { d =>
      boot(d)
      write(d, CSR.MCOUNTINHIBIT, 5) // 周期与退休禁止自动增长，计数器读写可独立核对。
      write(d, CSR.MCYCLE, 0)
      for (adr <- Seq(CSR.MSCRATCH, CSR.SSCRATCH, CSR.MCYCLE)) {
        var expected = BigInt(0)
        val rows = Seq((1, 1, BigInt(0x13579bdf)), (2, 1, BigInt(0x00ff0000)),
          (3, 1, BigInt(0x00070000)), (5, 13, BigInt(0)),
          (6, 18, BigInt(0)), (7, 9, BigInt(0)))
        for ((funct, rs, src) <- rows) {
          transaction(d, adr, funct, src, rs)
          d.io.IsCSRInstrM.expect(true.B)
          d.io.CSRReadM.expect(true.B)
          d.io.CSRWriteM.expect(true.B)
          d.io.CSRResultM.expect(expected.U) // 沿前旧值。
          val source = if (funct >= 5) BigInt(rs) else src
          expected = (funct % 4) match {
            case 1 => source
            case 2 => expected | source
            case 3 => expected & (wordMask ^ source)
          }
          d.clock.step()
          read(d, adr, expected)
        }
        transaction(d, adr, CSRFunction.RW, 0x55, rd = 0)
        d.io.CSRReadM.expect(false.B)
        d.io.CSRResultM.expect(0.U)
        d.io.CSRWriteM.expect(true.B)
        d.clock.step()
        for (funct <- Seq(CSRFunction.RS, CSRFunction.RC, CSRFunction.RSI, CSRFunction.RCI)) {
          transaction(d, adr, funct, 0xffffffffL, rs = 0)
          d.io.CSRWriteM.expect(false.B)
          d.io.CSRResultM.expect(0x55.U)
        }
      }
    }
  }

  it should "reject missing readonly and privilege accesses independently of traps" in {
    for (config <- Seq(PrivilegedConfig(), PrivilegedConfig(supervisorSupported = false))) {
      test(new CSRHarness(config)) { d =>
        boot(d)
        for (mode <- 0 to 3; adr <- Seq(CSR.MSCRATCH, CSR.SSCRATCH, 0xfff, CSR.MHARTID,
          CSR.CYCLE);
             funct <- Seq(CSRFunction.RW, CSRFunction.RS, CSRFunction.RC, CSRFunction.RWI,
               CSRFunction.RSI, CSRFunction.RCI); trap <- Seq(false, true)) {
          transaction(d, adr, funct, 1, mode = mode)
          d.io.TrapM.poke(trap.B)
          val missing = adr == 0xfff || (adr == CSR.SSCRATCH && !config.supervisorSupported)
          val lower = mode == Privilege.RESERVED || (adr == CSR.SSCRATCH &&
            mode == Privilege.USER) ||
            (Set(CSR.MSCRATCH, CSR.MHARTID)(adr) && mode != Privilege.MACHINE) ||
              (adr == CSR.CYCLE && mode != Privilege.MACHINE)
          val readonly = Set(CSR.MHARTID, CSR.CYCLE)(adr)
          d.io.IllegalCSRAccessM.expect((missing || lower || readonly).B)
        }
        read(d, CSR.MHARTID, 0)
        transaction(d, 0xfff, 1)
        d.io.ValidM.poke(false.B)
        d.io.IllegalCSRAccessM.expect(false.B)
      }
    }
  }

  it should "share CSR views and avoid capturing hardware pending in software" in {
    test(new CSRHarness) { d =>
      boot(d)
      write(d, CSR.MIDELEG, 0x222)
      write(d, CSR.MIE, 0xaaa)
      read(d, CSR.SIE, 0x222, mode = Privilege.SUPERVISOR)
      write(d, CSR.SIE, 2, mode = Privilege.SUPERVISOR)
      d.io.MIE_REGW.expect(0x88a.U)
      write(d, CSR.MSTATUS, 0x180a)
      read(d, CSR.SSTATUS, 2, mode = Privilege.SUPERVISOR)
      write(d, CSR.SSTATUS, 0x22, mode = Privilege.SUPERVISOR)
      d.io.MSTATUS_REGW.expect(0x182a.U)
      transaction(d, CSR.MIP, CSRFunction.RSI, rs = 2)
      d.io.SExtInt.poke(true.B)
      d.io.CSRResultM.expect(0x200.U)
      d.clock.step()
      read(d, CSR.MIP, 2)
      transaction(d, CSR.SIP, CSRFunction.RCI, rs = 2, mode = Privilege.SUPERVISOR)
      d.io.SExtInt.poke(true.B)
      d.io.CSRResultM.expect(0x202.U)
      d.clock.step()
      read(d, CSR.SIP, 0, mode = Privilege.SUPERVISOR)
      transaction(d, CSR.MIP, CSRFunction.RS, rs = 0)
      d.io.MExtInt.poke(true.B)
      d.io.MTimerInt.poke(true.B)
      d.io.MSwInt.poke(true.B)
      d.io.CSRResultM.expect(0x888.U)
    }
  }

  it should "cancel every software group for either trap destination" in {
    test(new CSRHarness) { d =>
      boot(d)
      write(d, CSR.MCOUNTINHIBIT, 5)
      val initial = Seq(CSR.MSCRATCH -> 0x11, CSR.SSCRATCH -> 0x22, CSR.MCYCLE -> 0x33,
        CSR.MIE -> 0xaaa, CSR.MIP -> 2, CSR.MSTATUS -> 0x40)
      for ((adr, value) <- initial) write(d, adr, value)
      for (next <- Seq(Privilege.SUPERVISOR, Privilege.MACHINE); adr <- initial.map(_._1)) {
        transaction(d, adr, CSRFunction.RW, 0xffffffffL)
        d.io.TrapM.poke(true.B)
        d.io.NextPrivilegeModeM.poke(next.U)
        d.io.CauseM.poke(8.U)
        d.clock.step()
        for ((a, value) <- initial.filterNot(_._1 == CSR.MSTATUS)) read(d, a, value)
        // 当前M不允许降到S；M陷阱只改变栈字段，UBE始终保持。
        assert(d.io.MSTATUS_REGW.peek().litValue.testBit(6))
      }
    }
  }

  it should "record exact EPC interrupt cause and cause-dependent tval in both modes" in {
    test(new CSRHarness) { d =>
      boot(d)
      for (next <- Seq(Privilege.SUPERVISOR, Privilege.MACHINE); interrupt <- Seq(false,
        true); cause <- 0 until 32) {
        idle(d)
        d.io.PrivilegeModeW.poke(Privilege.USER.U)
        d.io.NextPrivilegeModeM.poke(next.U)
        d.io.TrapM.poke(true.B)
        d.io.InterruptM.poke(interrupt.B)
        d.io.CauseM.poke(cause.U)
        d.io.PCM.poke(0x80001237L.U)
        d.io.InstrM.poke(0xdeadbeefL.U)
        d.io.IEUAdrM.poke(0xa5a5f00dL.U)
        d.clock.step()
        val base = if (next == Privilege.MACHINE) CSR.MSCRATCH else CSR.SSCRATCH
        read(d, base + 1, 0x80001234L)
        read(d, base + 2, BigInt(cause) + (if (interrupt) BigInt(1) << 31 else 0))
        val tval = if (interrupt || Set(8, 9, 11)(cause)) BigInt(0)
          else if (cause == 2) hex("deadbeef")
          else if (Set(1, 3, 12)(cause)) hex("80001237")
          else hex("a5a5f00d")
        read(d, base + 3, tval)
      }
    }
  }

  it should "select real WARL vectors and return EPC without owning current mode" in {
    for (vector <- Seq(false, true)) {
      test(new CSRHarness(PrivilegedConfig(vectoredInterruptsSupported = vector))) { d =>
        boot(d)
        write(d, CSR.MTVEC, 0x8000013dL)
        write(d, CSR.STVEC, 0x8000027dL)
        write(d, CSR.MEPC, 0x80001003L)
        write(d, CSR.SEPC, 0x80002003L)
        for (next <- Seq(Privilege.SUPERVISOR, Privilege.MACHINE); interrupt <- Seq(false,
          true); cause <- Seq(0, 5, 11, 31)) {
          idle(d)
          d.io.NextPrivilegeModeM.poke(next.U)
          d.io.InterruptM.poke(interrupt.B)
          d.io.CauseM.poke(cause.U)
          val base = if (next == Privilege.MACHINE) hex(if (vector) "80000100" else "8000013c")
            else hex(if (vector) "80000240" else "8000027c")
          d.io.TrapVectorM.expect((base + (if (vector && interrupt) 4 * cause else 0)).U)
          d.io.mretM.poke(true.B)
          d.io.EPCM.expect(0x80001000L.U)
          d.io.mretM.poke(false.B)
          d.io.EPCM.expect(0x80002000L.U)
        }
      }
    }
  }

  it should "hold committed state for invalid stall flush and synchronous reset" in {
    test(new CSRHarness) { d =>
      boot(d)
      write(d, CSR.MSCRATCH, 0x55)
      write(d, CSR.MEPC, 0x100)
      write(d, CSR.MIE, 0xaaa)
      for (valid <- Seq(false, true); stall <- Seq(false, true);
           flush <- Seq(false, true) if !valid || stall || flush;
           adr <- Seq(CSR.MSCRATCH, CSR.MSTATUS, CSR.MIE); trap <- Seq(false, true)) {
        transaction(d, adr, CSRFunction.RW, 0xffffffffL)
        d.io.ValidM.poke(valid.B)
        d.io.StallW.poke(stall.B)
        d.io.FlushM.poke(flush.B)
        d.io.TrapM.poke(trap.B)
        d.io.PCM.poke(0xabc.U)
        d.clock.step()
        read(d, CSR.MSCRATCH, 0x55)
        read(d, CSR.MEPC, 0x100)
        d.io.MIE_REGW.expect(0xaaa.U)
        d.io.MSTATUS_REGW.expect(0.U)
      }
      transaction(d, CSR.MIE, CSRFunction.RW, 0xffffffffL)
      d.reset.poke(true.B)
      d.io.MIE_REGW.expect(0xaaa.U)
      d.clock.step()
      d.io.MIE_REGW.expect(0.U)
      d.io.MSTATUS_REGW.expect(0.U)
      d.io.MCYCLE_REGW.expect(0.U)
      d.reset.poke(false.B)
      read(d, CSR.MSCRATCH, 0)
    }
  }

  it should "wire counter permissions inhibit retirement HPM events and time" in {
    test(new CSRHarness(PrivilegedConfig(hpmCounters = 1))) { d =>
      boot(d)
      write(d, CSR.MCOUNTINHIBIT, 5)
      write(d, CSR.MCYCLE, 0x89abcdefL)
      write(d, CSR.MCYCLEH, 0x01234567)
      d.io.MCYCLE_REGW.expect(hex("0123456789abcdef").U)
      transaction(d, CSR.TIME, CSRFunction.RS, rs = 0)
      d.io.TIME_REGW.poke(hex("fedcba9876543210").U)
      d.io.CSRResultM.expect(0x76543210L.U)
      d.io.InstrM.poke(csr(CSR.TIMEH, CSRFunction.RS, rs = 0).U)
      d.io.CSRResultM.expect(0xfedcba98L.U)
      transaction(d, CSR.CYCLE, CSRFunction.RS, rs = 0, mode = Privilege.SUPERVISOR)
      d.io.IllegalCSRAccessM.expect(true.B)
      write(d, CSR.MCOUNTEREN, 15)
      read(d, CSR.CYCLE, 0x89abcdefL, mode = Privilege.SUPERVISOR)
      transaction(d, CSR.CYCLE, CSRFunction.RS, rs = 0, mode = Privilege.USER)
      d.io.IllegalCSRAccessM.expect(true.B)
      write(d, CSR.SCOUNTEREN, 15)
      read(d, CSR.CYCLE, 0x89abcdefL, mode = Privilege.USER)
      write(d, CSR.MCOUNTINHIBIT, 1)
      idle(d)
      d.io.InstrRetiredM.poke(true.B)
      d.clock.step()
      d.io.MINSTRET_REGW.expect(1.U)
      d.io.TrapM.poke(true.B)
      d.clock.step()
      d.io.MINSTRET_REGW.expect(1.U)
      write(d, CSR.MHPMEVENT3, 1)
      idle(d)
      d.io.HPMEventsM.poke(1.U)
      d.clock.step(3)
      read(d, CSR.MHPMCOUNTER3, 3)
      read(d, CSR.MHPMCOUNTER4, 0) // 未激活槽是存在的零CSR。
    }
  }

  it should "route STCE bit63 comparator and Sstc permissions through real leaves" in {
    test(new CSRHarness(PrivilegedConfig(sstcSupported = true))) { d =>
      boot(d)
      write(d, CSR.MIDELEG, 0x20)
      write(d, CSR.MIP, 0x20)
      write(d, CSR.STIMECMP, 10)
      write(d, CSR.STIMECMPH, 1)
      transaction(d, CSR.STIMECMP, CSRFunction.RS, rs = 0, mode = Privilege.SUPERVISOR)
      d.io.IllegalCSRAccessM.expect(true.B)
      write(d, CSR.MCOUNTEREN, 2)
      transaction(d, CSR.STIMECMP, CSRFunction.RS, rs = 0, mode = Privilege.SUPERVISOR)
      d.io.IllegalCSRAccessM.expect(true.B)
      write(d, CSR.MENVCFGH, 0x80000000L)
      read(d, CSR.STIMECMP, 10, mode = Privilege.SUPERVISOR)
      d.io.MIP_REGW.expect(0.U)
      d.io.TIME_REGW.poke(hex("0000000100000009").U)
      d.io.MIP_REGW.expect(0.U)
      d.io.TIME_REGW.poke(hex("000000010000000a").U)
      d.io.MIP_REGW.expect(0x20.U)
      transaction(d, CSR.SIP, CSRFunction.RS, rs = 0, mode = Privilege.SUPERVISOR)
      d.io.TIME_REGW.poke(hex("000000010000000a").U)
      d.io.CSRResultM.expect(0x20.U)
      write(d, CSR.MENVCFGH, 0)
      d.io.MIP_REGW.expect(0x20.U) // 切换STCE保留内部软件STIP。
      write(d, CSR.MENVCFGH, 0x80000000L)
      write(d, CSR.MIP, 0x20) // STCE=1整字写清除潜藏软件STIP。
      write(d, CSR.MENVCFGH, 0)
      d.io.MIP_REGW.expect(0.U)
    }
  }

  it should "honor big endian configuration MPRV and status return stacks" in {
    for (big <- Seq(false, true)) {
      test(new CSRHarness(PrivilegedConfig(bigEndianSupported = big))) { d =>
        boot(d)
        write(d, CSR.MSTATUSH, 0x30)
        d.io.BigEndianM.expect(big.B)
        write(d, CSR.MSTATUS, 0x20040) // MPRV=1, MPP=U, UBE=1。
        d.io.BigEndianM.expect(big.B)
        write(d, CSR.MSTATUS, 0x20800) // MPRV=1, MPP=S；SBE由mstatush提供。
        d.io.BigEndianM.expect(big.B)
        write(d, CSR.MSTATUS, 0x1808)
        idle(d)
        d.io.TrapM.poke(true.B)
        d.clock.step()
        d.io.STATUS_MIE.expect(false.B)
        d.io.STATUS_MPP.expect(Privilege.MACHINE.U)
        idle(d)
        d.io.mretM.poke(true.B)
        d.clock.step()
        d.io.STATUS_MIE.expect(true.B)
        d.io.STATUS_MPP.expect(Privilege.USER.U)
        write(d, CSR.SSTATUS, 2)
        idle(d)
        d.io.PrivilegeModeW.poke(Privilege.SUPERVISOR.U)
        d.io.NextPrivilegeModeM.poke(Privilege.SUPERVISOR.U)
        d.io.TrapM.poke(true.B)
        d.clock.step()
        d.io.STATUS_SIE.expect(false.B)
        d.io.STATUS_SPP.expect(true.B)
        idle(d)
        d.io.PrivilegeModeW.poke(Privilege.SUPERVISOR.U)
        d.io.sretM.poke(true.B)
        d.clock.step()
        d.io.STATUS_SIE.expect(true.B)
        d.io.STATUS_SPP.expect(false.B)
      }
    }
  }

  it should "remove supervisor views while retaining UM counter permission and hart ID" in {
    test(new CSRHarness(PrivilegedConfig(supervisorSupported = false,
      hartId = hex("fedcba98")))) { d =>
      boot(d)
      read(d, CSR.MHARTID, hex("fedcba98"))
      for (adr <- Seq(CSR.SSTATUS, CSR.SIE, CSR.SSCRATCH, CSR.SATP, CSR.MEDELEG,
        CSR.MIDELEG, CSR.STIMECMP)) {
        transaction(d, adr, CSRFunction.RS, rs = 0)
        d.io.IllegalCSRAccessM.expect(true.B)
      }
      write(d, CSR.MIE, 0xfff)
      d.io.MIE_REGW.expect(0x888.U)
      write(d, CSR.MIP, 0xfff)
      d.io.MIP_REGW.expect(0.U)
      write(d, CSR.MCOUNTEREN, 7)
      write(d, CSR.MCOUNTINHIBIT, 5)
      read(d, CSR.CYCLE, d.io.MCYCLE_REGW.peek().litValue, mode = Privilege.USER)
      idle(d)
      d.io.mretM.poke(false.B)
      d.io.EPCM.expect(0.U)
    }
  }

  it should "validate all shared configuration bounds without weakening the public type" in {
    val legal = Seq(PrivilegedConfig(hpmCounters = 29, hartId = wordMask),
      PrivilegedConfig(wfiTimeoutCycles = 1), PrivilegedConfig(wfiTimeoutCycles = 1 << 24))
    assert(legal.size == 3)
    for (make <- Seq[() => PrivilegedConfig](
      () => PrivilegedConfig(supervisorSupported = false, sstcSupported = true),
      () => PrivilegedConfig(hpmCounters = -1), () => PrivilegedConfig(hpmCounters = 30),
      () => PrivilegedConfig(hartId = -1), () => PrivilegedConfig(hartId = BigInt(1) << 32),
      () => PrivilegedConfig(wfiTimeoutCycles = 0),
      () => PrivilegedConfig(wfiTimeoutCycles = (1 << 24) + 1))) {
      intercept[IllegalArgumentException](make())
    }
  }

  it should "generate stable explicit synchronous ports in representative configurations" in {
    val configs = Seq(PrivilegedConfig(), PrivilegedConfig(supervisorSupported = false),
      PrivilegedConfig(sstcSupported = true, hpmCounters = 1))
    var ports = Set.empty[String]
    for (config <- configs) {
      val rtl = (new ChiselStage).emitVerilog(new CSR(config),
        Array("--target-dir", Files.createTempDirectory("csr-rtl-").toString))
      val top = rtl.substring(rtl.lastIndexOf("module CSR("))
      val found = "(?m)^\\s*(?:input|output)\\s+(?:\\[\\d+:0\\]\\s+)?(\\w+)".r
        .findAllMatchIn(top).map(_.group(1)).toSet
      if (ports.isEmpty) ports = found else assert(found == ports)
      assert(found.size == 45 && found.contains("clk") && found.contains("reset"))
      assert(rtl.contains("always @(posedge clk)"))
      assert(!rtl.contains("posedge reset") && !rtl.contains("negedge"))
    }
  }
}
