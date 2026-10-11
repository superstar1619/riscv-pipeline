package riscvpipeline.privileged

import riscvpipeline.TestEncoding.{CSR, CSRFunction, Privilege}
import chisel3._
import chisel3.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.Files
import scala.language.reflectiveCalls
import scala.util.Random

private class CSRCountersHarness(s: Boolean = true, hpm: Int = 0) extends Module {
  val io = IO(new CSRCountersIO)
  val dut = Module(new CSRCounters(s, hpm))
  dut.clk := clock
  dut.reset := reset.asBool
  io <> dut.io
}

// 控制寄存器仍由Machine/Supervisor持有，测试层汇总地址并统一取消陷阱同拍软件写。
private class CounterAccessHarness extends Module {
  val io = IO(new Bundle {
    val Instr = Input(UInt(32.W))
    val Src = Input(UInt(32.W))
    val Mode = Input(UInt(2.W))
    val Valid = Input(Bool())
    val Stall = Input(Bool())
    val Flush = Input(Bool())
    val Trap = Input(Bool())
    val Retired = Input(Bool())
    val Events = Input(UInt(32.W))
    val Time = Input(UInt(64.W))
    val Result = Output(UInt(32.W))
    val Illegal = Output(Bool())
    val Cycle = Output(UInt(64.W))
    val Instret = Output(UInt(64.W))
    val HPM = Output(UInt(64.W))
    val Event = Output(UInt(32.W))
    val Inhibit = Output(UInt(32.W))
  })
  val access = Module(new CSRAccess)
  val counters = Module(new CSRCounters(true, 1))
  val machine = Module(new CSRMachine(hpmCounters = 1))
  val supervisor = Module(new CSRSupervisor(hpmCounters = 1))
  for ((clk, rst) <- Seq((machine.clk, machine.reset),
       (supervisor.clk, supervisor.reset), (counters.clk, counters.reset))) {
    clk := clock
    rst := reset.asBool
  }
  access.io.InstrM := io.Instr
  access.io.SrcAM := io.Src
  access.io.ValidM := io.Valid
  access.io.PrivilegeModeW := io.Mode
  access.io.CSRExistsM := counters.io.CSRCExistsM || machine.io.CSRMExistsM ||
    supervisor.io.CSRSExistsM
  access.io.CSRAccessAllowedM := Mux(counters.io.CSRCExistsM,
    counters.io.CSRCAccessAllowedM, Mux(supervisor.io.CSRSExistsM,
      supervisor.io.CSRSAccessAllowedM, true.B))
  access.io.CSRReadValM := Mux(counters.io.CSRCExistsM, counters.io.CSRCReadValM,
    Mux(supervisor.io.CSRSExistsM, supervisor.io.CSRSReadValM, machine.io.CSRMReadValM))
  access.io.CSRModifyValM := Mux(counters.io.CSRCExistsM, counters.io.CSRCModifyValM,
    Mux(supervisor.io.CSRSExistsM, supervisor.io.CSRSModifyValM, machine.io.CSRMModifyValM))
  for (leaf <- Seq(counters.io.ValidM, machine.io.ValidM, supervisor.io.ValidM)) {
    leaf := io.Valid
  }
  for (leaf <- Seq(counters.io.StallW, machine.io.StallW, supervisor.io.StallW)) {
    leaf := io.Stall
  }
  for (leaf <- Seq(counters.io.FlushM, machine.io.FlushM, supervisor.io.FlushM)) {
    leaf := io.Flush
  }
  for (leaf <- Seq(counters.io.CSRAdrM, machine.io.CSRAdrM, supervisor.io.CSRAdrM)) {
    leaf := access.io.CSRAdrM
  }
  for (leaf <- Seq(counters.io.CSRWriteM, machine.io.CSRWriteM, supervisor.io.CSRWriteM)) {
    leaf := access.io.CSRWriteM && !io.Trap
  }
  for (leaf <- Seq(counters.io.CSRWriteValM, machine.io.CSRWriteValM,
       supervisor.io.CSRWriteValM)) leaf := access.io.CSRWriteValM
  counters.io.PrivilegeModeW := io.Mode
  counters.io.TrapM := io.Trap
  counters.io.InstrRetiredM := io.Retired
  counters.io.HPMEventsM := io.Events
  counters.io.TIME_REGW := io.Time
  counters.io.MCOUNTINHIBIT_REGW := machine.io.MCOUNTINHIBIT_REGW
  counters.io.MCOUNTEREN_REGW := machine.io.MCOUNTEREN_REGW
  counters.io.SCOUNTEREN_REGW := supervisor.io.SCOUNTEREN_REGW
  machine.io.MTrapM := io.Trap
  supervisor.io.STrapM := false.B
  for (leaf <- Seq(machine.io.NextEPCM, supervisor.io.NextEPCM)) leaf := 0.U
  for (leaf <- Seq(machine.io.NextCauseM, supervisor.io.NextCauseM)) leaf := 0.U
  for (leaf <- Seq(machine.io.NextXtvalM, supervisor.io.NextXtvalM)) leaf := 0.U
  machine.io.MSTATUS_REGW := 0.U
  machine.io.MSTATUSH_REGW := 0.U
  machine.io.MIP_REGW := 0.U
  machine.io.MIE_REGW := 0.U
  machine.io.MIP_REGW_writeable := 0.U
  supervisor.io.PrivilegeModeW := io.Mode
  supervisor.io.STATUS_TVM := false.B
  supervisor.io.ENVCFG_STCE := false.B
  supervisor.io.MCOUNTEREN_TM := machine.io.MCOUNTEREN_REGW(1)
  supervisor.io.TIME_REGW := io.Time
  supervisor.io.SSTATUS_REGW := 0.U
  supervisor.io.SIP_REGW := 0.U
  supervisor.io.SIE_REGW := 0.U
  supervisor.io.SIP_REGW_writeable := 0.U
  io.Result := access.io.CSRResultM
  io.Illegal := access.io.IllegalCSRAccessM
  io.Cycle := counters.io.MCYCLE_REGW
  io.Instret := counters.io.MINSTRET_REGW
  io.HPM := counters.io.HPMCOUNTER_REGW(0)
  io.Event := counters.io.HPMEVENT_REGW(0)
  io.Inhibit := machine.io.MCOUNTINHIBIT_REGW
}

class CSRCountersSpec extends AnyFlatSpec with ChiselScalatestTester {
  private val full = BigInt("ffffffff", 16)
  private val full64 = BigInt("ffffffffffffffff", 16)
  private val indices = Seq(0, 2) ++ (3 to 31)
  private val machines = indices.flatMap(n => Seq(CSR.MCYCLE + n, CSR.MCYCLEH + n)).toSet
  private val events = (CSR.MHPMEVENT3 to CSR.MHPMEVENT31).toSet
  private val shadows = ((CSR.CYCLE to CSR.HPMCOUNTER31) ++ (CSR.CYCLEH to CSR.HPMCOUNTER31H)).toSet
  private val addresses = (machines ++ events ++ shadows).toSeq.sorted
  private case class Inputs(adr: Int = CSR.MCYCLE, word: BigInt = 0, write: Boolean = false,
    valid: Boolean = true, stall: Boolean = false, flush: Boolean = false,
    trap: Boolean = false, retired: Boolean = false, pulses: BigInt = 0,
    mode: Int = Privilege.MACHINE, inhibit: BigInt = 0, men: BigInt = full, sen: BigInt = full,
    time: BigInt = 0)
  private def drive(d: CSRCountersHarness, a: Inputs): Unit = {
    d.io.CSRAdrM.poke(a.adr.U)
    d.io.CSRWriteValM.poke(a.word.U)
    d.io.CSRWriteM.poke(a.write.B)
    d.io.ValidM.poke(a.valid.B)
    d.io.StallW.poke(a.stall.B)
    d.io.FlushM.poke(a.flush.B)
    d.io.TrapM.poke(a.trap.B)
    d.io.InstrRetiredM.poke(a.retired.B)
    d.io.HPMEventsM.poke(a.pulses.U)
    d.io.PrivilegeModeW.poke(a.mode.U)
    d.io.MCOUNTINHIBIT_REGW.poke(a.inhibit.U)
    d.io.MCOUNTEREN_REGW.poke(a.men.U)
    d.io.SCOUNTEREN_REGW.poke(a.sen.U)
    d.io.TIME_REGW.poke(a.time.U)
  }
  private def boot(d: CSRCountersHarness): Unit = {
    drive(d, Inputs(valid = false))
    d.reset.poke(true.B)
    d.clock.step()
    d.reset.poke(false.B)
    d.io.MCYCLE_REGW.expect(0.U)
    d.io.MINSTRET_REGW.expect(0.U)
    for (i <- 0 until 29) {
      d.io.HPMCOUNTER_REGW(i).expect(0.U)
      d.io.HPMEVENT_REGW(i).expect(0.U)
    }
  }
  private def cycle(d: CSRCountersHarness, a: Inputs): Unit = {
    drive(d, a)
    d.clock.step()
  }
  private def read(d: CSRCountersHarness, a: Inputs, value: BigInt,
                   exists: Boolean = true, allowed: Boolean = true): Unit = {
    drive(d, a)
    d.io.CSRCExistsM.expect(exists.B)
    d.io.CSRCAccessAllowedM.expect(allowed.B)
    d.io.CSRCReadValM.expect(value.U)
    d.io.CSRCModifyValM.expect(value.U)
  }
  private def write64(d: CSRCountersHarness, n: Int, v: BigInt): Unit = {
    cycle(d, Inputs(adr = CSR.MCYCLE + n, word = v & full, write = true, inhibit = full))
    cycle(d, Inputs(adr = CSR.MCYCLEH + n, word = v >> 32, write = true, inhibit = full))
  }
  private def allowed(s: Boolean, a: Inputs): Boolean = addresses.contains(a.adr) &&
    (a.mode == Privilege.MACHINE || (shadows(a.adr) && a.men.testBit(a.adr % 32) &&
      ((s && a.mode == Privilege.SUPERVISOR) || (a.mode == Privilege.USER && (!s ||
        a.sen.testBit(a.adr % 32))))))

  // 独立参考模型：64位算术与按地址字典的半字替换，不复用RTL译码/选择器。
  private class Model(s: Boolean, hpm: Int) {
    var counts = Map(0 -> BigInt(0), 2 -> BigInt(0)) ++
      (3 to 31).map(_ -> BigInt(0)).toMap
    var selectors = (3 to 31).map(_ -> 0).toMap
    def expect(d: CSRCountersHarness, a: Inputs): Unit = {
      d.io.MCYCLE_REGW.expect(counts(0).U)
      d.io.MINSTRET_REGW.expect(counts(2).U)
      for (n <- 3 to 31) {
        d.io.HPMCOUNTER_REGW(n - 3).expect(counts(n).U)
        d.io.HPMEVENT_REGW(n - 3).expect(selectors(n).U)
      }
      val value = if (!allowed(s, a)) BigInt(0) else if (events(a.adr)) {
        BigInt(selectors(a.adr - CSR.MCOUNTINHIBIT))
      } else {
        val n = a.adr % 32
        val counter = if (n == 1) a.time else counts(n)
        if ((a.adr / 128) % 2 == 1) counter / (BigInt(1) << 32) else counter & full
      }
      read(d, a, value, addresses.contains(a.adr), allowed(s, a))
    }
    def update(a: Inputs, resetting: Boolean): Unit = {
      if (resetting) {
        counts = counts.view.mapValues(_ => BigInt(0)).toMap
        selectors = selectors.view.mapValues(_ => 0).toMap
      } else {
        val writable = a.write && a.valid && !a.stall && !a.flush && !a.trap &&
          a.mode == Privilege.MACHINE && machines(a.adr)
        for (n <- Seq(0, 2) ++ (3 until hpm + 3)) {
          val event = if (n == 0) true else if (n == 2) {
            a.retired && a.valid && !a.stall && !a.flush && !a.trap
          } else selectors(n) > 0 && a.pulses.testBit(selectors(n) - 1)
          val old = counts(n)
          val next = if (writable && a.adr % 32 == n) {
            if ((a.adr / 128) % 2 == 1) (a.word << 32) + (old & full)
            else (old / (BigInt(1) << 32) << 32) + a.word
          } else if (event && !a.inhibit.testBit(n)) (old + 1) & full64 else old
          counts += n -> next
        }
        if (a.write && a.valid && !a.stall && !a.flush && !a.trap &&
          a.mode == Privilege.MACHINE &&
            events(a.adr) && a.adr - CSR.MCOUNTINHIBIT < hpm + 3) {
          selectors += (a.adr - CSR.MCOUNTINHIBIT) -> (if (a.word <= 32) a.word.toInt else 0)
        }
      }
    }
  }

  behavior of "CSRCounters"

  it should "synchronously reset owned state without resetting external time" in {
    test(new CSRCountersHarness(hpm = 1)) { d =>
      boot(d)
      write64(d, 0, full64)
      write64(d, 2, full64)
      write64(d, 3, full64)
      cycle(d, Inputs(adr = CSR.MHPMEVENT3, word = 32, write = true, inhibit = full))
      drive(d, Inputs(adr = CSR.TIME, time = full64, write = true, retired = true,
        pulses = full))
      d.reset.poke(true.B)
      d.io.MCYCLE_REGW.expect(full64.U)
      d.io.MINSTRET_REGW.expect(full64.U)
      d.io.HPMCOUNTER_REGW(0).expect(full64.U)
      d.io.CSRCReadValM.expect(full.U)
      d.clock.step()
      d.io.MCYCLE_REGW.expect(0.U)
      d.io.MINSTRET_REGW.expect(0.U)
      d.io.HPMCOUNTER_REGW(0).expect(0.U)
      d.io.HPMEVENT_REGW(0).expect(0.U)
      d.io.CSRCReadValM.expect(full.U)
    }
  }

  it should "recognize 155 addresses and reject mtime holes and extension addresses" in {
    assert(addresses.size == 155)
    for ((s, hpm) <- Seq((false, 0), (true, 0), (true, 1), (true, 29))) {
      test(new CSRCountersHarness(s, hpm)) { d =>
        boot(d)
        for (adr <- 0 until 4096) {
          val a = Inputs(adr = adr, mode = Privilege.RESERVED, valid = false, write = true)
          read(d, a, 0, addresses.contains(adr), allowed = false)
        }
      }
    }
  }

  it should "check every address against modes and independent permission bits" in {
    for (s <- Seq(false, true)) {
      test(new CSRCountersHarness(s)) { d =>
        boot(d)
        for (adr <- addresses; mode <- 0 to 3; m <- Seq(false, true);
             u <- Seq(false, true)) {
          val bit = BigInt(1) << (adr % 32)
          val a = Inputs(adr = adr, mode = mode, men = if (m) bit else 0,
            sen = if (u) bit else 0, time = full64, valid = false, write = true)
          val permit = if (mode == Privilege.MACHINE) true else shadows(adr) && m &&
            ((s && mode == Privilege.SUPERVISOR) || (mode == Privilege.USER && (!s || u)))
          val v = if (permit && Set(CSR.TIME, CSR.TIMEH)(adr)) full else BigInt(0)
          read(d, a, v, allowed = permit)
        }
      }
    }
  }

  it should "count cycles through bubbles stalls flushes traps and denied access" in {
    test(new CSRCountersHarness) { d =>
      boot(d)
      for (i <- 1 to 16) {
        cycle(d, Inputs(adr = 0xb01, write = true, valid = (i & 1) != 0,
          stall = (i & 2) != 0, flush = (i & 4) != 0, trap = (i & 8) != 0,
          mode = Privilege.RESERVED, men = 0, sen = 0))
        d.io.MCYCLE_REGW.expect(i.U)
        d.io.MINSTRET_REGW.expect(0.U)
      }
    }
  }

  it should "count retirement candidates once only when valid and allowed to commit" in {
    test(new CSRCountersHarness) { d =>
      boot(d)
      var expected = 0
      for (valid <- Seq(false, true); stall <- Seq(false, true); flush <- Seq(false, true);
           trap <- Seq(false, true); retired <- Seq(false, true)) {
        cycle(d, Inputs(valid = valid, stall = stall, flush = flush,
          trap = trap, retired = retired))
        if (valid && !stall && !flush && !trap && retired) expected += 1
        d.io.MINSTRET_REGW.expect(expected.U)
      }
      for (_ <- 0 until 4) cycle(d, Inputs(retired = true, stall = true))
      d.io.MINSTRET_REGW.expect(1.U)
      cycle(d, Inputs(retired = true))
      d.io.MINSTRET_REGW.expect(2.U)
    }
  }

  it should "carry from bit31 and wrap at bit63 for cycle instret and active HPM" in {
    test(new CSRCountersHarness(hpm = 1)) { d =>
      boot(d)
      cycle(d, Inputs(adr = CSR.MHPMEVENT3, word = 1, write = true, inhibit = full))
      for (value <- Seq(full, full64)) {
        for (n <- Seq(0, 2, 3)) write64(d, n, value)
        cycle(d, Inputs(retired = true, pulses = 1))
        val wanted = if (value == full) BigInt(1) << 32 else BigInt(0)
        d.io.MCYCLE_REGW.expect(wanted.U)
        d.io.MINSTRET_REGW.expect(wanted.U)
        d.io.HPMCOUNTER_REGW(0).expect(wanted.U)
      }
    }
  }

  it should "apply inhibit by counter index without affecting time or software writes" in {
    test(new CSRCountersHarness(hpm = 29)) { d =>
      boot(d)
      for (n <- 3 to 31) {
        cycle(d, Inputs(adr = CSR.MCOUNTINHIBIT + n, word = 1, write = true, inhibit = full))
      }
      cycle(d, Inputs(retired = true, pulses = 1, inhibit = full, time = full64))
      d.io.MCYCLE_REGW.expect(0.U)
      d.io.MINSTRET_REGW.expect(0.U)
      for (i <- 0 until 29) d.io.HPMCOUNTER_REGW(i).expect(0.U)
      cycle(d, Inputs(retired = true, pulses = 1, inhibit = BigInt("55555555", 16)))
      d.io.MCYCLE_REGW.expect(0.U)
      d.io.MINSTRET_REGW.expect(0.U)
      for (n <- 3 to 31) {
        d.io.HPMCOUNTER_REGW(n - 3).expect((if (n % 2 == 1) 1 else 0).U)
      }
      cycle(d, Inputs(retired = false, pulses = 0, inhibit = 2))
      d.io.MCYCLE_REGW.expect(1.U)
      read(d, Inputs(adr = CSR.TIMEH, time = full64, inhibit = full), full)
      write64(d, 0, full64)
      d.io.MCYCLE_REGW.expect(full64.U)
    }
  }

  it should "give low and high writes priority over all 64 bits of concurrent increment" in {
    test(new CSRCountersHarness(hpm = 1)) { d =>
      boot(d)
      cycle(d, Inputs(adr = CSR.MHPMEVENT3, word = 1, write = true, inhibit = full))
      for (n <- Seq(0, 2, 3)) {
        write64(d, n, BigInt("12345678ffffffff", 16))
        cycle(d, Inputs(adr = CSR.MCYCLE + n, word = 0x89abcdefL, write = true,
          retired = true, pulses = 1))
        val port = if (n == 0) d.io.MCYCLE_REGW else if (n == 2) d.io.MINSTRET_REGW
          else d.io.HPMCOUNTER_REGW(0)
        port.expect(BigInt("1234567889abcdef", 16).U)
        write64(d, n, full64)
        cycle(d, Inputs(adr = CSR.MCYCLEH + n, word = 0x76543210L, write = true,
          retired = true, pulses = 1))
        port.expect(BigInt("76543210ffffffff", 16).U)
      }
    }
  }

  it should "continue updating other counters when one counter is written" in {
    test(new CSRCountersHarness(hpm = 1)) { d =>
      boot(d)
      cycle(d, Inputs(adr = CSR.MHPMEVENT3, word = 1, write = true, inhibit = full))
      cycle(d, Inputs(adr = CSR.MCYCLE, word = 7, write = true, retired = true, pulses = 1))
      d.io.MCYCLE_REGW.expect(7.U)
      d.io.MINSTRET_REGW.expect(1.U)
      d.io.HPMCOUNTER_REGW(0).expect(1.U)
      cycle(d, Inputs(adr = CSR.MINSTRET, word = 9, write = true, retired = true, pulses = 1))
      d.io.MCYCLE_REGW.expect(8.U)
      d.io.MINSTRET_REGW.expect(9.U)
      d.io.HPMCOUNTER_REGW(0).expect(2.U)
    }
  }

  it should "block software writes without stopping independent events" in {
    test(new CSRCountersHarness(hpm = 1)) { d =>
      boot(d)
      cycle(d, Inputs(adr = CSR.MHPMEVENT3, word = 1, write = true, inhibit = full))
      for (adr <- Seq(CSR.MCYCLE, CSR.MCYCLEH, CSR.MINSTRET, CSR.MINSTRETH,
        CSR.MHPMCOUNTER3, CSR.MHPMCOUNTER3H, CSR.MHPMEVENT3);
           blocked <- 0 until 4) {
        val oldCycle = d.io.MCYCLE_REGW.peek().litValue
        val oldRetired = d.io.MINSTRET_REGW.peek().litValue
        val oldHpm = d.io.HPMCOUNTER_REGW(0).peek().litValue
        cycle(d, Inputs(adr = adr, word = full, write = true, valid = blocked != 0,
          stall = blocked == 1, flush = blocked == 2, trap = blocked == 3,
          retired = true, pulses = 1))
        d.io.MCYCLE_REGW.expect((oldCycle + 1).U)
        d.io.MINSTRET_REGW.expect(oldRetired.U)
        d.io.HPMCOUNTER_REGW(0).expect((oldHpm + 1).U)
        d.io.HPMEVENT_REGW(0).expect(1.U)
      }
    }
  }

  it should "protect all state from U S reserved modes aliases and unknown CSR writes" in {
    test(new CSRCountersHarness(hpm = 1)) { d =>
      boot(d)
      write64(d, 0, 0x1234)
      write64(d, 2, 0x5678)
      write64(d, 3, 0xabcd)
      cycle(d, Inputs(adr = CSR.MHPMEVENT3, word = 32, write = true, inhibit = full))
      for (mode <- 0 to 3; adr <- Seq(CSR.MCYCLE, CSR.MCYCLEH, CSR.MINSTRET, CSR.MINSTRETH,
        CSR.MHPMCOUNTER3, CSR.MHPMCOUNTER3H,
           CSR.MHPMEVENT3, CSR.CYCLE, CSR.CYCLEH, CSR.TIME, CSR.TIMEH, CSR.INSTRET,
             CSR.INSTRETH, CSR.HPMCOUNTER3, CSR.HPMCOUNTER3H,
           0xb01, 0xb81, 0x723, CSR.MSTATUS)) {
        if (mode != Privilege.MACHINE || !Set(CSR.MCYCLE, CSR.MCYCLEH, CSR.MINSTRET,
          CSR.MINSTRETH, CSR.MHPMCOUNTER3, CSR.MHPMCOUNTER3H, CSR.MHPMEVENT3)(adr)) {
          cycle(d, Inputs(adr = adr, mode = mode, word = full, write = true, inhibit = full))
          d.io.MCYCLE_REGW.expect(0x1234.U)
          d.io.MINSTRET_REGW.expect(0x5678.U)
          d.io.HPMCOUNTER_REGW(0).expect(0xabcd.U)
          d.io.HPMEVENT_REGW(0).expect(32.U)
        }
      }
    }
  }

  it should "read both halves of the same machine and user shadow value before the edge" in {
    test(new CSRCountersHarness(hpm = 1)) { d =>
      boot(d)
      val values = Seq(0 -> BigInt("89abcdef01234567", 16),
        2 -> BigInt("fedcba9876543210", 16), 3 -> BigInt("8000000000000001", 16))
      for ((n, v) <- values) write64(d, n, v)
      for ((n, v) <- values; bank <- Seq(CSR.MCYCLE, CSR.CYCLE); high <- Seq(false, true)) {
        val adr = bank + n + (if (high) 0x80 else 0)
        read(d, Inputs(adr = adr, valid = false, stall = true, flush = true,
          retired = true, write = true), if (high) v >> 32 else v & full)
      }
    }
  }

  it should "expose coherent external time halves while all counters are inhibited" in {
    test(new CSRCountersHarness) { d =>
      boot(d)
      for (v <- Seq(BigInt(0), full, BigInt(1) << 32,
           BigInt("8000000000000001", 16), full64)) {
        read(d, Inputs(adr = CSR.TIME, time = v, valid = false, stall = true,
          flush = true, trap = true, inhibit = full), v & full)
        read(d, Inputs(adr = CSR.TIMEH, time = v, inhibit = full), v >> 32)
      }
      read(d, Inputs(adr = 0xb01, time = full64), 0, exists = false, allowed = false)
      read(d, Inputs(adr = 0xb81, time = full64), 0, exists = false, allowed = false)
    }
  }

  it should "select every event including bit31 while event zero never increments" in {
    test(new CSRCountersHarness(hpm = 1)) { d =>
      boot(d)
      cycle(d, Inputs(pulses = full, retired = true, inhibit = 1))
      d.io.HPMCOUNTER_REGW(0).expect(0.U)
      var count = 0
      for (id <- 1 to 32) {
        cycle(d, Inputs(adr = CSR.MHPMEVENT3, word = id, write = true, inhibit = full))
        d.io.HPMEVENT_REGW(0).expect(id.U)
        cycle(d, Inputs(pulses = BigInt(1) << (id - 1), inhibit = 1))
        count += 1
        d.io.HPMCOUNTER_REGW(0).expect(count.U)
        cycle(d, Inputs(pulses = full ^ (BigInt(1) << (id - 1)), inhibit = 1))
        d.io.HPMCOUNTER_REGW(0).expect(count.U)
      }
      cycle(d, Inputs(adr = CSR.MHPMEVENT3, word = 0, write = true, inhibit = full))
      cycle(d, Inputs(pulses = full))
      d.io.HPMCOUNTER_REGW(0).expect(32.U)
    }
  }

  it should "legalize selectors to zero and use the old selector on writes" in {
    test(new CSRCountersHarness(hpm = 1)) { d =>
      boot(d)
      cycle(d, Inputs(adr = CSR.MHPMEVENT3, word = 1, write = true, inhibit = full))
      cycle(d, Inputs(adr = CSR.MHPMEVENT3, word = 2, write = true, pulses = 1))
      d.io.HPMCOUNTER_REGW(0).expect(1.U)
      cycle(d, Inputs(pulses = 1))
      d.io.HPMCOUNTER_REGW(0).expect(1.U)
      cycle(d, Inputs(pulses = 2))
      d.io.HPMCOUNTER_REGW(0).expect(2.U)
      for (word <- Seq(BigInt(33), full, BigInt(0x10001), BigInt(0x80000020L))) {
        cycle(d, Inputs(adr = CSR.MHPMEVENT3, word = word, write = true, inhibit = full))
        read(d, Inputs(adr = CSR.MHPMEVENT3), 0)
        cycle(d, Inputs(pulses = full))
        d.io.HPMCOUNTER_REGW(0).expect(2.U)
      }
    }
  }

  it should "keep inactive HPM slots zero and ignore legal machine writes" in {
    for (hpm <- Seq(0, 1, 3, 29)) {
      test(new CSRCountersHarness(hpm = hpm)) { d =>
        boot(d)
        for (n <- hpm + 3 to 31; adr <- Seq(CSR.MCYCLE + n, CSR.MCYCLEH + n,
          CSR.MCOUNTINHIBIT + n)) {
          cycle(d, Inputs(adr = adr, word = full, write = true, pulses = full))
          read(d, Inputs(adr = adr), 0)
          d.io.HPMCOUNTER_REGW(n - 3).expect(0.U)
          d.io.HPMEVENT_REGW(n - 3).expect(0.U)
        }
      }
    }
  }

  it should "connect CSRAccess and real control registers for permissions and RMW" in {
    test(new CounterAccessHarness) { d =>
      def csr(adr: Int, funct: Int, rs: Int = 1): BigInt =
        (BigInt(adr) << 20) | (BigInt(rs) << 15) | (BigInt(funct) << 12) | 0xf3
      def set(instr: BigInt, src: BigInt = 0, mode: Int = Privilege.MACHINE,
              retired: Boolean = false, pulses: BigInt = 0, trap: Boolean = false): Unit = {
        d.io.Instr.poke(instr.U)
        d.io.Src.poke(src.U)
        d.io.Mode.poke(mode.U)
        d.io.Valid.poke(true.B)
        d.io.Stall.poke(false.B)
        d.io.Flush.poke(false.B)
        d.io.Trap.poke(trap.B)
        d.io.Retired.poke(retired.B)
        d.io.Events.poke(pulses.U)
        d.io.Time.poke(BigInt("1234567889abcdef", 16).U)
      }
      set(0)
      d.reset.poke(true.B)
      d.clock.step()
      d.reset.poke(false.B)
      set(csr(CSR.CYCLE, CSRFunction.RS, 0), mode = Privilege.SUPERVISOR)
      d.io.Illegal.expect(true.B)
      set(csr(CSR.MCOUNTEREN, CSRFunction.RW), 15)
      d.clock.step()
      set(csr(CSR.TIME, CSRFunction.RS, 0), mode = Privilege.SUPERVISOR)
      d.io.Illegal.expect(false.B)
      d.io.Result.expect(BigInt("89abcdef", 16).U)
      set(csr(CSR.TIME, CSRFunction.RS, 0), mode = Privilege.USER)
      d.io.Illegal.expect(true.B)
      set(csr(CSR.SCOUNTEREN, CSRFunction.RW), 15, mode = Privilege.SUPERVISOR)
      d.clock.step()
      set(csr(CSR.TIMEH, CSRFunction.RS, 0), mode = Privilege.USER)
      d.io.Illegal.expect(false.B)
      d.io.Result.expect(0x12345678.U)
      set(csr(CSR.MCOUNTINHIBIT, CSRFunction.RW), 15)
      d.clock.step()
      d.io.Inhibit.expect(13.U)
      val frozen = d.io.Cycle.peek().litValue
      set(csr(CSR.MCYCLE, CSRFunction.RW), 0x12)
      d.io.Result.expect(frozen.U)
      d.clock.step()
      set(csr(CSR.MCYCLE, CSRFunction.RS), 0x80)
      d.io.Result.expect(0x12.U)
      d.clock.step()
      set(csr(CSR.MCYCLE, CSRFunction.RC), 0x10)
      d.io.Result.expect(0x92.U)
      d.clock.step()
      d.io.Cycle.expect(0x82.U)
      set(csr(CSR.CYCLE, CSRFunction.RW), 0, mode = Privilege.USER)
      d.io.Illegal.expect(true.B)
      d.clock.step()
      d.io.Cycle.expect(0x82.U)
      set(csr(CSR.MINSTRET, CSRFunction.RW), 9, retired = true)
      d.clock.step()
      d.io.Instret.expect(9.U)
      set(csr(CSR.INSTRET, CSRFunction.RS, 0), retired = true)
      d.io.Result.expect(9.U)
      d.clock.step()
      d.io.Instret.expect(9.U) // IR目前被禁止增长。
      set(csr(CSR.MHPMEVENT3, CSRFunction.RW), 32)
      d.clock.step()
      set(csr(CSR.MCOUNTINHIBIT, CSRFunction.RW), 1)
      d.clock.step()
      set(0, retired = true, pulses = BigInt(1) << 31)
      d.clock.step()
      d.io.Instret.expect(10.U)
      d.io.HPM.expect(1.U)
      set(csr(CSR.MCYCLE, CSRFunction.RW), full, retired = true, pulses = BigInt(1) << 31,
        trap = true)
      d.clock.step()
      d.io.Cycle.expect(0x82.U)
      d.io.Instret.expect(10.U)
      d.io.HPM.expect(2.U)
      d.io.Event.expect(32.U)
    }
  }

  it should "match a seeded independent model across S and HPM boundary configurations" in {
    for ((s, hpm) <- Seq((false, 0), (false, 1), (true, 0), (true, 3), (true, 29))) {
      test(new CSRCountersHarness(s, hpm)) { d =>
        boot(d)
        val model = new Model(s, hpm)
        val random = new Random(0x8f812026L)
        val choices = addresses ++ Seq(0xb01, 0xb81, 0x723, CSR.MSTATUS, 0xfff)
        for (i <- 0 until 512) {
          val adr = choices(random.nextInt(choices.size))
          val word = if (events(adr) && random.nextBoolean()) BigInt(random.nextInt(35))
            else BigInt(32, random)
          val a = Inputs(adr = adr, word = word, write = random.nextBoolean(),
            valid = i % 13 != 0, stall = i % 17 == 0, flush = i % 19 == 0,
            trap = i % 23 == 0, retired = random.nextBoolean(), pulses = BigInt(32, random),
            mode = random.nextInt(4), inhibit = BigInt(32, random),
            men = BigInt(32, random), sen = BigInt(32, random), time = BigInt(64, random))
          val resetting = i % 127 == 0
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

  it should "generate stable 80-port synchronous RTL for zero and maximum HPM" in {
    val in = Map("ValidM" -> 1, "StallW" -> 1, "FlushM" -> 1, "TrapM" -> 1,
      "CSRWriteM" -> 1, "CSRAdrM" -> 12, "CSRWriteValM" -> 32,
      "PrivilegeModeW" -> 2, "InstrRetiredM" -> 1, "HPMEventsM" -> 32,
      "MCOUNTINHIBIT_REGW" -> 32, "MCOUNTEREN_REGW" -> 32,
      "SCOUNTEREN_REGW" -> 32, "TIME_REGW" -> 64)
    val out = Map("CSRCExistsM" -> 1, "CSRCAccessAllowedM" -> 1,
      "CSRCReadValM" -> 32, "CSRCModifyValM" -> 32, "MCYCLE_REGW" -> 64,
      "MINSTRET_REGW" -> 64) ++ (0 until 29).flatMap(i =>
      Seq(s"HPMCOUNTER_REGW_$i" -> 64, s"HPMEVENT_REGW_$i" -> 32))
    for ((s, hpm) <- Seq((false, 0), (true, 0), (false, 29), (true, 29))) {
      val rtl = (new ChiselStage).emitVerilog(new CSRCounters(s, hpm),
        Array("--target-dir", Files.createTempDirectory("csr-counters-rtl-").toString))
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
    }
  }

  it should "reject HPM counts outside the architectural 0 to 29 range" in {
    for (n <- Seq(-1, 30)) {
      intercept[IllegalArgumentException] {
        (new ChiselStage).emitChirrtl(new CSRCounters(hpmCounters = n))
      }
    }
  }
}
