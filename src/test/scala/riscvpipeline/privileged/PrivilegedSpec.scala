package riscvpipeline.privileged

import riscvpipeline.TestEncoding.{CSR, CSRFunction, Opcode, Privilege}
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

private class PrivilegedHarness(c: PrivilegedConfig) extends Module {
  val io = IO(new PrivilegedIO)
  val dut = Module(new Privileged(c))
  dut.clk := clock
  dut.reset := reset.asBool
  io <> dut.io
}

class PrivilegedSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  private val flags = Seq("StallW", "FlushM", "CommittedM", "CommittedF", "wfiW",
    "MExtInt", "SExtInt", "MTimerInt", "MSwInt", "InstrMisalignedFaultM",
    "InstrAccessFaultM", "InstrPageFaultM", "IllegalIEUInstrM", "BreakpointFaultM",
    "LoadMisalignedFaultM", "StoreAmoMisalignedFaultM", "LoadAccessFaultM",
    "StoreAmoAccessFaultM", "LoadPageFaultM", "StoreAmoPageFaultM")
  private def drive(d: PrivilegedHarness, instr: BigInt = 0x13,
                    pc: Int = 0x40, src: BigInt = 0, addr: Int = 0): Unit = {
    flags.foreach(n => d.io.elements(n).asInstanceOf[Bool].poke(false.B))
    d.io.ValidM.poke(true.B)
    d.io.InstrRetiredM.poke(true.B)
    d.io.InstrM.poke(instr.U)
    d.io.PCM.poke(pc.U)
    d.io.SrcAM.poke(src.U)
    d.io.IEUAdrM.poke(addr.U)
    d.io.TIME_REGW.poke(0.U)
    d.io.HPMEventsM.poke(0.U)
  }
  private def boot(d: PrivilegedHarness): Unit = {
    drive(d)
    d.io.ValidM.poke(false.B)
    d.reset.poke(true.B)
    d.clock.step()
    d.reset.poke(false.B)
    d.io.PrivilegeModeW.expect(Privilege.MACHINE.U)
    drive(d)
  }
  private def csr(adr: Int, f: Int = CSRFunction.RW, rd: Int = 0, rs: Int = 1): BigInt =
    (BigInt(adr) << 20) | (BigInt(rs) << 15) | (BigInt(f) << 12) |
      (BigInt(rd) << 7) | Opcode.SYSTEM
  private def write(d: PrivilegedHarness, adr: Int, word: BigInt): Unit = {
    drive(d, csr(adr), src = word)
    d.io.TrapM.expect(false.B)
    d.io.CSRWriteM.expect(true.B)
    d.io.RedirectM.expect(true.B)
    d.io.RedirectPCM.expect(0x44.U)
    d.clock.step()
  }

  behavior of "Privileged"

  it should "commit real CSR old values and redirect only successful writes" in {
    test(new PrivilegedHarness(PrivilegedConfig())) { d =>
      boot(d)
      write(d, CSR.MSCRATCH, 0x55)
      drive(d, csr(CSR.MSCRATCH, f = CSRFunction.RS, rd = 5, rs = 0))
      d.io.CSRResultM.expect(0x55.U)
      d.io.CSRReadM.expect(true.B)
      d.io.CSRWriteM.expect(false.B)
      d.io.RedirectM.expect(false.B)
      drive(d, csr(CSR.CYCLE, f = CSRFunction.RW))
      d.io.TrapM.expect(true.B)
      d.io.CauseM.expect(2.U)
      d.io.CSRWriteM.expect(false.B)
    }
  }

  it should "take ECALL and restore old MPP at the same edge as the status stack" in {
    test(new PrivilegedHarness(PrivilegedConfig())) { d =>
      boot(d)
      write(d, CSR.MTVEC, 0x100)
      write(d, CSR.MEPC, 0x80)
      write(d, CSR.MSTATUS, 0x80)
      drive(d, 0x30200073)
      d.io.RetM.expect(true.B)
      d.io.RedirectPCM.expect(0x80.U)
      d.io.NextPrivilegeModeM.expect(Privilege.USER.U)
      d.clock.step()
      d.io.PrivilegeModeW.expect(Privilege.USER.U)
      drive(d, 0x73, pc = 0x84)
      d.io.TrapM.expect(true.B)
      d.io.CauseM.expect(8.U)
      d.io.RedirectPCM.expect(0x100.U)
      d.clock.step()
      d.io.PrivilegeModeW.expect(Privilege.MACHINE.U)
      drive(d, 0x30200073)
      d.io.RedirectPCM.expect(0x84.U)
      d.clock.step()
      d.io.PrivilegeModeW.expect(Privilege.USER.U)
    }
  }

  it should "delegate U ECALL to S and return through the real supervisor EPC" in {
    test(new PrivilegedHarness(PrivilegedConfig())) { d =>
      boot(d)
      write(d, CSR.STVEC, 0x200)
      write(d, CSR.MEDELEG, 0x100)
      write(d, CSR.MEPC, 0x80)
      write(d, CSR.MSTATUS, 0)
      drive(d, 0x30200073)
      d.clock.step()
      drive(d, 0x73, pc = 0x90)
      d.io.RedirectPCM.expect(0x200.U)
      d.io.NextPrivilegeModeM.expect(Privilege.SUPERVISOR.U)
      d.clock.step()
      d.io.PrivilegeModeW.expect(Privilege.SUPERVISOR.U)
      drive(d, 0x10200073)
      d.io.RetM.expect(true.B)
      d.io.RedirectPCM.expect(0x90.U)
      d.clock.step()
      d.io.PrivilegeModeW.expect(Privilege.USER.U)
    }
  }

  it should "select a vectored M interrupt and respect pending local and global enables" in {
    test(new PrivilegedHarness(PrivilegedConfig())) { d =>
      boot(d)
      write(d, CSR.MTVEC, 0x101)
      write(d, CSR.MIE, 0x880)
      drive(d)
      d.io.MExtInt.poke(true.B)
      d.io.TrapM.expect(false.B)
      write(d, CSR.MSTATUS, 8)
      drive(d)
      d.io.MTimerInt.poke(true.B)
      d.io.MExtInt.poke(true.B)
      d.io.InterruptM.expect(true.B)
      d.io.CauseM.expect(11.U)
      d.io.RedirectPCM.expect(0x12c.U)
      d.clock.step()
      drive(d, 0x30200073)
      d.io.RedirectPCM.expect(0x40.U)
      d.clock.step()
      d.io.PrivilegeModeW.expect(Privilege.MACHINE.U)
    }
  }

  it should "wake WFI on a local pending interrupt before accepting it at the successor" in {
    test(new PrivilegedHarness(PrivilegedConfig(wfiTimeoutCycles = 8))) { d =>
      boot(d)
      write(d, CSR.MIE, 0x80)
      write(d, CSR.MSTATUS, 8)
      drive(d, 0x10500073, pc = 0x40)
      d.io.WFIStallM.expect(true.B)
      d.io.StallW.poke(true.B)
      d.io.InstrRetiredM.poke(false.B)
      d.clock.step(3)
      d.io.MTimerInt.poke(true.B)
      d.io.WFIStallM.expect(false.B)
      d.io.InterruptM.expect(false.B)
      d.io.StallW.poke(false.B)
      d.io.InstrRetiredM.poke(true.B)
      d.clock.step()
      drive(d, pc = 0x44)
      d.io.MTimerInt.poke(true.B)
      d.io.wfiW.poke(true.B)
      d.io.InterruptM.expect(true.B)
      d.io.CauseM.expect(7.U)
      d.clock.step()
      drive(d, 0x30200073)
      d.io.RedirectPCM.expect(0x44.U)
    }
  }

  it should "resolve a TW timeout into an illegal trap without a feedback loop" in {
    test(new PrivilegedHarness(PrivilegedConfig(wfiTimeoutCycles = 3))) { d =>
      boot(d)
      write(d, CSR.MIE, 0x80)
      write(d, CSR.MSTATUS, 0x200800)
      drive(d, 0x30200073)
      d.clock.step()
      d.io.PrivilegeModeW.expect(Privilege.SUPERVISOR.U)
      drive(d, 0x10500073, pc = 0x60)
      d.io.StallW.poke(true.B)
      d.clock.step(3)
      d.io.WFITimeoutM.expect(true.B)
      d.io.IllegalInstrFaultM.expect(true.B)
      d.io.TrapM.expect(true.B)
      d.io.CauseM.expect(2.U)
      d.io.WFIStallM.expect(false.B)
      d.clock.step(2)
      d.io.PrivilegeModeW.expect(Privilege.SUPERVISOR.U)
      d.io.RedirectM.expect(false.B)
      d.io.WFITimeoutM.expect(true.B)
      d.io.IllegalInstrFaultM.expect(true.B)
      d.io.MTimerInt.poke(true.B)
      d.clock.step()
      d.io.TrapM.expect(true.B)
      d.io.CauseM.expect(2.U)
      d.io.StallW.poke(false.B)
      d.clock.step()
      d.io.PrivilegeModeW.expect(Privilege.MACHINE.U)
      drive(d, 0x30200073)
      d.io.RedirectPCM.expect(0x60.U)
      d.io.WFITimeoutM.expect(false.B)
    }
  }

  it should "suppress canceled redirects and reset mode and counters synchronously" in {
    test(new PrivilegedHarness(PrivilegedConfig())) { d =>
      boot(d)
      for (flush <- Seq(false, true)) {
        drive(d, csr(CSR.MSCRATCH), src = 0x99)
        d.io.FlushM.poke(flush.B)
        d.io.StallW.poke((!flush).B)
        d.io.RedirectM.expect(false.B)
        d.clock.step()
      }
      drive(d, csr(CSR.MSCRATCH, CSRFunction.RS, 1, 0))
      d.io.CSRResultM.expect(0.U)
      write(d, CSR.MSCRATCH, 0x55)
      drive(d, csr(CSR.MSCRATCH, CSRFunction.RS, 1, 0))
      d.reset.poke(true.B)
      d.io.CSRReadM.expect(true.B)
      d.io.CSRResultM.expect(0x55.U)
      drive(d, csr(CSR.MSCRATCH), src = 0x99)
      d.io.CSRWriteM.expect(true.B)
      d.io.RedirectM.expect(true.B)
      d.clock.step()
      d.io.PrivilegeModeW.expect(Privilege.MACHINE.U)
      d.io.MSTATUS_REGW.expect(0.U)
      d.io.MCYCLE_REGW.expect(0.U)
      d.io.MINSTRET_REGW.expect(0.U)
      drive(d, csr(CSR.MSCRATCH, CSRFunction.RS, 1, 0))
      d.io.CSRResultM.expect(0.U)
      d.reset.poke(false.B)
    }
  }

  it should "retain M trap priority over return and work with S support disabled" in {
    test(new PrivilegedHarness(PrivilegedConfig(supervisorSupported = false))) { d =>
      boot(d)
      write(d, CSR.MEPC, 0x80)
      drive(d, 0x30200073, pc = 0x44)
      d.io.MSTATUS_REGW.expect(0.U)
      d.io.InstrAccessFaultM.poke(true.B)
      d.io.CauseM.expect(1.U)
      d.io.NextPrivilegeModeM.expect(Privilege.MACHINE.U)
      d.clock.step()
      d.io.PrivilegeModeW.expect(Privilege.MACHINE.U)
      drive(d, 0x10200073)
      d.io.IllegalInstrFaultM.expect(true.B)
      d.io.CauseM.expect(2.U)
    }
  }
}
