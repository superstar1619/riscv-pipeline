package riscvpipeline.privileged

import riscvpipeline.TestEncoding.{CSR, CSRFunction, Cause, Privilege}
import chisel3._
import chisel3.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.Files
import scala.language.reflectiveCalls
import scala.util.Random

private case class SupervisorParams(s: Boolean = true, vm: Boolean = false,
  stc: Boolean = false, vector: Boolean = true, hpm: Int = 0, asid: Int = 0)

private class CSRSupervisorHarness(p: SupervisorParams = SupervisorParams()) extends Module {
  val io = IO(new CSRSupervisorIO)
  val dut = Module(new CSRSupervisor(p.s, p.vm, p.stc, p.vector, p.hpm, p.asid))
  dut.clk := clock
  dut.reset := reset.asBool
  io <> dut.io
}

// 五个真实叶模块仅在测试层连接；全局Trap取消全部组的软件写。
private class SupervisorAccessHarness extends Module {
  val io = IO(new Bundle {
    val Instr = Input(UInt(32.W))
    val Src = Input(UInt(32.W))
    val Mode = Input(UInt(2.W))
    val Valid = Input(Bool())
    val Stall = Input(Bool())
    val Flush = Input(Bool())
    val MTrap = Input(Bool())
    val STrap = Input(Bool())
    val Time = Input(UInt(64.W))
    val SExt = Input(Bool())
    val Result = Output(UInt(32.W))
    val Illegal = Output(Bool())
    val MIPSoftware = Output(UInt(12.W))
    val SIP = Output(UInt(12.W))
    val SIE = Output(UInt(12.W))
    val Status = Output(UInt(32.W))
    val MEPC = Output(UInt(32.W))
    val SEPC = Output(UInt(32.W))
    val SCause = Output(UInt(32.W))
    val STval = Output(UInt(32.W))
    val SScratch = Output(UInt(32.W))
    val Satp = Output(UInt(32.W))
    val Compare = Output(UInt(64.W))
  })
  val access = Module(new CSRAccess)
  val machine = Module(new CSRMachine(true, true, true))
  val supervisor = Module(new CSRSupervisor(true, true, true, true, 0, 9))
  val status = Module(new CSRStatus(true, true))
  val interrupts = Module(new CSRInterrupt(true, true))
  for ((clk, reset) <- Seq((machine.clk, machine.reset),
       (supervisor.clk, supervisor.reset), (status.clk, status.reset),
       (interrupts.clk, interrupts.reset))) {
    clk := clock
    reset := this.reset.asBool
  }
  access.io.InstrM := io.Instr
  access.io.SrcAM := io.Src
  access.io.ValidM := io.Valid
  access.io.PrivilegeModeW := io.Mode
  access.io.CSRExistsM := machine.io.CSRMExistsM || supervisor.io.CSRSExistsM
  access.io.CSRAccessAllowedM := Mux(supervisor.io.CSRSExistsM,
    supervisor.io.CSRSAccessAllowedM, true.B)
  access.io.CSRReadValM := Mux(supervisor.io.CSRSExistsM,
    supervisor.io.CSRSReadValM, machine.io.CSRMReadValM)
  access.io.CSRModifyValM := Mux(supervisor.io.CSRSExistsM,
    supervisor.io.CSRSModifyValM, machine.io.CSRMModifyValM)
  val Trap = io.MTrap || io.STrap
  for (leaf <- Seq(machine.io.ValidM, supervisor.io.ValidM, status.io.ValidM,
       interrupts.io.ValidM)) leaf := io.Valid
  for (leaf <- Seq(machine.io.StallW, supervisor.io.StallW, status.io.StallW,
       interrupts.io.StallW)) leaf := io.Stall
  for (leaf <- Seq(machine.io.FlushM, supervisor.io.FlushM, status.io.FlushM,
       interrupts.io.FlushM)) leaf := io.Flush
  for (leaf <- Seq(machine.io.CSRWriteValM, supervisor.io.CSRWriteValM,
       status.io.CSRWriteValM, interrupts.io.CSRWriteValM)) leaf := access.io.CSRWriteValM
  for (leaf <- Seq(machine.io.CSRAdrM, supervisor.io.CSRAdrM)) leaf := access.io.CSRAdrM
  for (leaf <- Seq(machine.io.CSRWriteM, supervisor.io.CSRWriteM)) {
    leaf := access.io.CSRWriteM && !Trap
  }
  for (leaf <- Seq(machine.io.NextEPCM, supervisor.io.NextEPCM)) leaf := 0x807.U
  for (leaf <- Seq(machine.io.NextCauseM, supervisor.io.NextCauseM)) leaf := 37.U
  for (leaf <- Seq(machine.io.NextXtvalM, supervisor.io.NextXtvalM)) leaf := 0xcafe.U
  machine.io.MTrapM := io.MTrap
  supervisor.io.STrapM := io.STrap
  machine.io.MSTATUS_REGW := status.io.MSTATUS_REGW
  machine.io.MSTATUSH_REGW := status.io.MSTATUSH_REGW
  machine.io.MIP_REGW := interrupts.io.MIP_REGW
  machine.io.MIE_REGW := interrupts.io.MIE_REGW
  machine.io.MIP_REGW_writeable := interrupts.io.MIP_REGW_writeable
  supervisor.io.PrivilegeModeW := io.Mode
  supervisor.io.STATUS_TVM := status.io.STATUS_TVM
  supervisor.io.ENVCFG_STCE := machine.io.MENVCFG_REGW(63)
  supervisor.io.MCOUNTEREN_TM := machine.io.MCOUNTEREN_REGW(1)
  supervisor.io.TIME_REGW := io.Time
  supervisor.io.SSTATUS_REGW := status.io.SSTATUS_REGW
  supervisor.io.SIP_REGW := interrupts.io.SIP_REGW
  supervisor.io.SIE_REGW := interrupts.io.SIE_REGW
  supervisor.io.SIP_REGW_writeable := interrupts.io.SIP_REGW_writeable
  status.io.WriteMSTATUSM := machine.io.WriteMSTATUSM
  status.io.WriteMSTATUSHM := machine.io.WriteMSTATUSHM
  status.io.WriteSSTATUSM := supervisor.io.WriteSSTATUSM
  status.io.TrapM := Trap
  status.io.PrivilegeModeW := io.Mode
  status.io.NextPrivilegeModeM := Mux(io.STrap, 1.U(2.W), 3.U(2.W))
  status.io.mretM := false.B
  status.io.sretM := false.B
  status.io.SelHPTW := false.B
  interrupts.io.WriteMIEM := machine.io.WriteMIEM
  interrupts.io.WriteMIPM := machine.io.WriteMIPM
  interrupts.io.WriteSIEM := supervisor.io.WriteSIEM
  interrupts.io.WriteSIPM := supervisor.io.WriteSIPM
  interrupts.io.MIDELEG_REGW := machine.io.MIDELEG_REGW
  interrupts.io.MExtInt := false.B
  interrupts.io.SExtInt := io.SExt
  interrupts.io.MTimerInt := false.B
  interrupts.io.STimerInt := supervisor.io.STimerInt
  interrupts.io.MSwInt := false.B
  interrupts.io.ENVCFG_STCE := machine.io.MENVCFG_REGW(63)
  io.Result := access.io.CSRResultM
  io.Illegal := access.io.IllegalCSRAccessM
  io.MIPSoftware := interrupts.io.MIP_REGW_writeable
  io.SIP := interrupts.io.SIP_REGW
  io.SIE := interrupts.io.SIE_REGW
  io.Status := status.io.MSTATUS_REGW
  io.MEPC := machine.io.MEPC_REGW
  io.SEPC := supervisor.io.SEPC_REGW
  io.SCause := supervisor.io.SCAUSE_REGW
  io.STval := supervisor.io.STVAL_REGW
  io.SScratch := supervisor.io.SSCRATCH_REGW
  io.Satp := supervisor.io.SATP_REGW
  io.Compare := supervisor.io.STIMECMP_REGW
}

class CSRSupervisorSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  private def hex(s: String): BigInt = BigInt(s, 16)
  private val full = hex("ffffffff")
  private val full64 = hex("ffffffffffffffff")
  private val base = Seq(CSR.SSTATUS, CSR.SIE, CSR.STVEC, CSR.SCOUNTEREN, CSR.SENVCFG,
    CSR.SSCRATCH,
    CSR.SEPC, CSR.SCAUSE, CSR.STVAL, CSR.SIP, CSR.SATP)
  private val owned = Seq(CSR.STVEC, CSR.SCOUNTEREN, CSR.SENVCFG, CSR.SSCRATCH, CSR.SEPC,
    CSR.SCAUSE, CSR.STVAL,
    CSR.SATP, CSR.STIMECMP, CSR.STIMECMPH)
  private case class Inputs(adr: Int = CSR.SSCRATCH, word: BigInt = 0, write: Boolean = false,
    trap: Boolean = false, epc: BigInt = 0, cause: Int = 0, tval: BigInt = 0,
    valid: Boolean = true, stall: Boolean = false, flush: Boolean = false,
    mode: Int = Privilege.SUPERVISOR, tvm: Boolean = false, stce: Boolean = true,
      tm: Boolean = true,
    time: BigInt = 0, status: BigInt = 0, sip: Int = 0, sie: Int = 0, sw: Int = 0)
  private def drive(d: CSRSupervisorHarness, a: Inputs): Unit = {
    d.io.CSRAdrM.poke(a.adr.U)
    d.io.CSRWriteValM.poke(a.word.U)
    d.io.CSRWriteM.poke(a.write.B)
    d.io.STrapM.poke(a.trap.B)
    d.io.NextEPCM.poke(a.epc.U)
    d.io.NextCauseM.poke(a.cause.U)
    d.io.NextXtvalM.poke(a.tval.U)
    d.io.ValidM.poke(a.valid.B)
    d.io.StallW.poke(a.stall.B)
    d.io.FlushM.poke(a.flush.B)
    d.io.PrivilegeModeW.poke(a.mode.U)
    d.io.STATUS_TVM.poke(a.tvm.B)
    d.io.ENVCFG_STCE.poke(a.stce.B)
    d.io.MCOUNTEREN_TM.poke(a.tm.B)
    d.io.TIME_REGW.poke(a.time.U)
    d.io.SSTATUS_REGW.poke(a.status.U)
    d.io.SIP_REGW.poke(a.sip.U)
    d.io.SIE_REGW.poke(a.sie.U)
    d.io.SIP_REGW_writeable.poke(a.sw.U)
  }
  private def checkState(d: CSRSupervisorHarness, v: Map[Int, BigInt] = Map.empty): Unit = {
    for ((adr, port) <- Seq(CSR.STVEC -> d.io.STVEC_REGW,
      CSR.SCOUNTEREN -> d.io.SCOUNTEREN_REGW,
         CSR.SENVCFG -> d.io.SENVCFG_REGW, CSR.SSCRATCH -> d.io.SSCRATCH_REGW,
         CSR.SEPC -> d.io.SEPC_REGW, CSR.SCAUSE -> d.io.SCAUSE_REGW,
           CSR.STVAL -> d.io.STVAL_REGW,
         CSR.SATP -> d.io.SATP_REGW)) port.expect(v.getOrElse(adr, BigInt(0)).U)
    d.io.STIMECMP_REGW.expect(((v.getOrElse(CSR.STIMECMPH, BigInt(0)) << 32) +
      v.getOrElse(CSR.STIMECMP, BigInt(0))).U)
  }
  private def strobes(d: CSRSupervisorHarness, status: Boolean = false,
                      sie: Boolean = false, sip: Boolean = false): Unit = {
    d.io.WriteSSTATUSM.expect(status.B)
    d.io.WriteSIEM.expect(sie.B)
    d.io.WriteSIPM.expect(sip.B)
  }
  private def boot(d: CSRSupervisorHarness): Unit = {
    drive(d, Inputs(valid = false))
    d.reset.poke(true.B)
    d.clock.step()
    d.reset.poke(false.B)
    checkState(d)
  }
  private def cycle(d: CSRSupervisorHarness, a: Inputs): Unit = {
    drive(d, a)
    d.clock.step()
  }
  private def read(d: CSRSupervisorHarness, adr: Int, value: BigInt,
                   exists: Boolean = true, allowed: Boolean = true,
                   modify: Option[BigInt] = None): Unit = {
    d.io.CSRAdrM.poke(adr.U)
    d.io.CSRSExistsM.expect(exists.B)
    d.io.CSRSAccessAllowedM.expect(allowed.B)
    d.io.CSRSReadValM.expect(value.U)
    d.io.CSRSModifyValM.expect(modify.getOrElse(value).U)
  }

  // 独立字段模型：地址字典、允许位集合及算术对齐；不使用硬件助手/掩码。
  private class Model(p: SupervisorParams) {
    var state: Map[Int, BigInt] = owned.map(_ -> BigInt(0)).toMap
    private def select(word: BigInt, positions: Seq[Int]): BigInt =
      positions.filter(word.testBit).map(i => BigInt(1) << i).sum
    def exists(adr: Int): Boolean = p.s &&
      (base.contains(adr) || (p.stc && Set(CSR.STIMECMP, CSR.STIMECMPH)(adr)))
    def allowed(a: Inputs): Boolean = exists(a.adr) && Set(1, 3)(a.mode) &&
      (a.adr != CSR.SATP || a.mode == Privilege.MACHINE || !a.tvm) &&
      (!Set(CSR.STIMECMP, CSR.STIMECMPH)(a.adr) || a.mode == Privilege.MACHINE || (a.stce &&
        a.tm))
    def compare: BigInt = (state(CSR.STIMECMPH) << 32) + state(CSR.STIMECMP)
    def expect(d: CSRSupervisorHarness, a: Inputs): Unit = {
      checkState(d, state)
      val value = if (!allowed(a)) BigInt(0) else a.adr match {
        case CSR.SSTATUS => a.status
        case CSR.SIE => BigInt(a.sie)
        case CSR.SIP => BigInt(a.sip)
        case adr => state.getOrElse(adr, BigInt(0))
      }
      val modify = if (allowed(a) && a.adr == CSR.SIP) BigInt(a.sw) else value
      read(d, a.adr, value, exists(a.adr), allowed(a), Some(modify))
      val active = a.write && a.valid && !a.stall && !a.flush &&
        !a.trap && allowed(a)
      strobes(d, active && a.adr == CSR.SSTATUS, active && a.adr == CSR.SIE,
        active && a.adr == CSR.SIP)
      d.io.STimerInt.expect((p.s && p.stc && a.time >= compare).B)
    }
    def update(a: Inputs, resetting: Boolean): Unit = {
      if (resetting) state = state.view.mapValues(_ => BigInt(0)).toMap
      else if (p.s && a.valid && !a.stall && !a.flush) {
        if (a.trap) {
          if (Set(Privilege.USER,
            Privilege.SUPERVISOR)(a.mode)) state ++= Map(CSR.SEPC -> (a.epc / 4 * 4),
            CSR.SCAUSE -> (BigInt(a.cause % Cause.INPUT_INTERRUPT_FLAG) +
              (if (a.cause >= Cause.INPUT_INTERRUPT_FLAG) BigInt(1) << 31 else BigInt(0))),
                CSR.STVAL -> a.tval)
        } else if (a.write && allowed(a) && owned.contains(a.adr)) {
          val next = a.adr match {
            case CSR.STVEC => if (p.vector && a.word.testBit(0)) a.word / 64 * 64 + 1
              else a.word / 4 * 4
            case CSR.SEPC => a.word / 4 * 4
            case CSR.SCAUSE => select(a.word, Seq(31) ++ (0 to 4))
            case CSR.SCOUNTEREN => select(a.word, (0 to 2) ++ (3 until p.hpm + 3))
            case CSR.SENVCFG => if (p.vm && a.word.testBit(0)) BigInt(1) else BigInt(0)
            case CSR.SATP => if (p.vm && a.word.testBit(31))
              select(a.word, Seq(31) ++ (0 to 21) ++ (22 until 22 + p.asid)) else BigInt(0)
            case _ => a.word
          }
          state += a.adr -> next
        }
      }
    }
  }

  behavior of "CSRSupervisor"

  it should "reset every owned register synchronously without gating forwarded writes" in {
    test(new CSRSupervisorHarness(SupervisorParams(vm = true, stc = true, asid = 9))) { d =>
      boot(d)
      for (address <- owned) cycle(d, Inputs(adr = address, word = full, write = true))
      val oldScratch = d.io.SSCRATCH_REGW.peek().litValue
      assert(oldScratch == full)
      d.reset.poke(true.B)
      d.io.SSCRATCH_REGW.expect(full.U)
      for (address <- Seq(CSR.SSTATUS, CSR.SIE, CSR.SIP)) {
        drive(d, Inputs(adr = address, word = full, write = true))
        strobes(d, status = address == CSR.SSTATUS, sie = address == CSR.SIE,
          sip = address == CSR.SIP)
      }
      drive(d, Inputs(word = full, write = true))
      strobes(d)
      d.clock.step()
      checkState(d)
      d.io.STimerInt.expect(true.B)
      d.reset.poke(false.B)
    }
  }

  it should "decode all addresses independently of privilege and extension permissions" in {
    for (p <- Seq(SupervisorParams(s = false), SupervisorParams(),
         SupervisorParams(stc = true))) {
      test(new CSRSupervisorHarness(p)) { d =>
        boot(d)
        drive(d, Inputs(mode = Privilege.USER, tvm = true, stce = false, tm = false))
        for (adr <- 0 until 4096) {
          val exists = p.s && (base.contains(adr) || (p.stc && Set(CSR.STIMECMP,
            CSR.STIMECMPH)(adr)))
          read(d, adr, 0, exists, allowed = false)
        }
      }
    }
  }

  it should "check M S U reserved modes TVM and both Sstc permissions without write loops" in {
    test(new CSRSupervisorHarness(SupervisorParams(vm = true, stc = true))) { d =>
      boot(d)
      for (mode <- 0 to 3; tvm <- Seq(false, true); stce <- Seq(false, true);
           tm <- Seq(false, true); adr <- base ++ Seq(CSR.STIMECMP, CSR.STIMECMPH, 0xfff)) {
        drive(d, Inputs(adr = adr, mode = mode, tvm = tvm, stce = stce, tm = tm,
          write = true, word = full))
        val exists = adr != 0xfff
        val permittedMode = mode == Privilege.SUPERVISOR || mode == Privilege.MACHINE
        val extra = if (adr == CSR.SATP) mode == Privilege.MACHINE || !tvm
          else if (adr == CSR.STIMECMP || adr == CSR.STIMECMPH) mode == Privilege.MACHINE ||
            (stce && tm) else true
        val allow = exists && permittedMode && extra
        d.io.CSRSExistsM.expect(exists.B)
        d.io.CSRSAccessAllowedM.expect(allow.B)
        strobes(d, allow && adr == CSR.SSTATUS, allow && adr == CSR.SIE, allow &&
          adr == CSR.SIP)
      }
    }
  }

  it should "forward shared S read values and use software-only SIP modification values" in {
    test(new CSRSupervisorHarness) { d =>
      boot(d)
      drive(d, Inputs(status = hex("89abcdef"), sip = 0x220, sie = 0x222, sw = 0x020,
        valid = false, stall = true, flush = true))
      read(d, CSR.SSTATUS, hex("89abcdef"))
      read(d, CSR.SIE, 0x222)
      read(d, CSR.SIP, 0x220, modify = Some(BigInt(0x020)))
      strobes(d)
      d.io.PrivilegeModeW.poke(Privilege.USER.U)
      read(d, CSR.SIP, 0, allowed = false)
    }
  }

  it should "preserve full scratch and tval data and format software EPC and cause" in {
    test(new CSRSupervisorHarness) { d =>
      boot(d)
      for ((adr, word, want) <- Seq((CSR.SSCRATCH, hex("89abcdef"), hex("89abcdef")),
           (CSR.STVAL, full, full), (CSR.SEPC, hex("80000003"), hex("80000000")),
           (CSR.SCAUSE, full, hex("8000001f")), (CSR.SCAUSE, hex("7fffffe7"), BigInt(7)))) {
        cycle(d, Inputs(adr = adr, word = word, write = true))
        read(d, adr, want)
      }
      for (low <- 0 to 3) {
        cycle(d, Inputs(adr = CSR.SEPC, word = 0x12345678L + low, write = true))
        read(d, CSR.SEPC, hex("12345678"))
      }
    }
  }

  it should "legalize every STVEC mode with direct and vectored alignment" in {
    for (vector <- Seq(false, true)) {
      test(new CSRSupervisorHarness(SupervisorParams(vector = vector))) { d =>
        boot(d)
        for ((word, vectored, direct) <- Seq(
             (hex("8000013c"), hex("8000013c"), hex("8000013c")),
             (hex("8000013d"), hex("80000101"), hex("8000013c")),
             (hex("8000013e"), hex("8000013c"), hex("8000013c")),
             (hex("8000013f"), hex("80000101"), hex("8000013c")),
             (full, hex("ffffffc1"), hex("fffffffc")))) {
          cycle(d, Inputs(adr = CSR.STVEC, word = word, write = true))
          read(d, CSR.STVEC, if (vector) vectored else direct)
        }
      }
    }
  }

  it should "atomically record U and S traps before any software or forwarded write" in {
    test(new CSRSupervisorHarness) { d =>
      boot(d)
      cycle(d, Inputs(word = hex("12345678"), write = true))
      for (mode <- Seq(0, 1); address <- owned ++ Seq(CSR.SSTATUS, CSR.SIE, CSR.SIP, 0xfff)) {
        cycle(d, Inputs(adr = address, word = full, write = true, trap = true,
          mode = mode, epc = hex("80001237"), cause = 37, tval = hex("a5a5f00d")))
        checkState(d, Map(CSR.SSCRATCH -> hex("12345678"), CSR.SEPC -> hex("80001234"),
          CSR.SCAUSE -> hex("80000005"), CSR.STVAL -> hex("a5a5f00d")))
        strobes(d)
      }
    }
  }

  it should "reject downward or reserved raw S trap events without falling into writes" in {
    test(new CSRSupervisorHarness) { d =>
      boot(d)
      cycle(d, Inputs(word = 0x55, write = true))
      for (mode <- Seq(2, 3); adr <- Seq(CSR.SSCRATCH, CSR.SSTATUS, CSR.SIE, CSR.SIP)) {
        cycle(d, Inputs(adr = adr, mode = mode, trap = true, write = true,
          word = full, epc = full, cause = 63, tval = full))
        checkState(d, Map(CSR.SSCRATCH -> BigInt(0x55)))
        strobes(d)
      }
    }
  }

  it should "format all 64 trap cause encodings" in {
    test(new CSRSupervisorHarness) { d =>
      boot(d)
      for (cause <- 0 until 64) {
        cycle(d, Inputs(trap = true, cause = cause))
        read(d, CSR.SCAUSE, BigInt(cause % Cause.INPUT_INTERRUPT_FLAG) +
          (if (cause >= Cause.INPUT_INTERRUPT_FLAG) BigInt(1) << 31 else BigInt(0)))
      }
    }
  }

  it should "hold all software and trap state for invalid stall and flush combinations" in {
    test(new CSRSupervisorHarness(SupervisorParams(vm = true, stc = true))) { d =>
      boot(d)
      cycle(d, Inputs(adr = CSR.SEPC, word = 0x104, write = true))
      for (valid <- Seq(false, true); stall <- Seq(false, true);
           flush <- Seq(false, true) if !valid || stall || flush;
           address <- owned; trap <- Seq(false, true)) {
        cycle(d, Inputs(adr = address, word = full, write = true, trap = trap,
          epc = full, cause = 63, tval = full, valid = valid, stall = stall, flush = flush))
        checkState(d, Map(CSR.SEPC -> BigInt(0x104)))
        strobes(d)
      }
    }
  }

  it should "mask user counter access controls by the configured HPM count" in {
    for ((hpm, mask) <- Seq((0, BigInt(7)), (1, BigInt(15)), (3, BigInt(63)), (29, full))) {
      test(new CSRSupervisorHarness(SupervisorParams(hpm = hpm))) { d =>
        boot(d)
        cycle(d, Inputs(adr = CSR.SCOUNTEREN, word = full, write = true))
        read(d, CSR.SCOUNTEREN, mask)
      }
    }
  }

  it should "keep unsupported environment fields zero and allow FIOM only with VM" in {
    for (vm <- Seq(false, true)) {
      test(new CSRSupervisorHarness(SupervisorParams(vm = vm))) { d =>
        boot(d)
        cycle(d, Inputs(adr = CSR.SENVCFG, word = full, write = true))
        read(d, CSR.SENVCFG, if (vm) 1 else 0)
        cycle(d, Inputs(adr = CSR.SENVCFG, word = 0xfffffffeL, write = true))
        read(d, CSR.SENVCFG, 0)
      }
    }
  }

  it should "implement Bare SATP as an existing zero CSR when VM is disabled" in {
    test(new CSRSupervisorHarness) { d =>
      boot(d)
      for (word <- Seq(BigInt(0), BigInt(1), full, hex("80000000"), hex("7fffffff"))) {
        cycle(d, Inputs(adr = CSR.SATP, word = word, write = true))
        read(d, CSR.SATP, 0)
      }
      drive(d, Inputs(adr = CSR.SATP, mode = Privilege.SUPERVISOR, tvm = true, write = true,
        word = full))
      read(d, CSR.SATP, 0, allowed = false)
      drive(d, Inputs(adr = CSR.SATP, mode = Privilege.MACHINE, tvm = true))
      read(d, CSR.SATP, 0)
    }
  }

  it should "mask Sv32 ASID low bits retain PPN and canonicalize Bare writes" in {
    for ((asid, mask) <- Seq((0, hex("803fffff")), (1, hex("807fffff")),
         (4, hex("83ffffff")), (9, full))) {
      test(new CSRSupervisorHarness(SupervisorParams(vm = true, asid = asid))) { d =>
        boot(d)
        cycle(d, Inputs(adr = CSR.SATP, word = full, write = true))
        read(d, CSR.SATP, mask)
        drive(d, Inputs(adr = CSR.SATP, word = 0, write = true, tvm = true))
        read(d, CSR.SATP, 0, allowed = false)
        d.clock.step()
        drive(d, Inputs(adr = CSR.SATP))
        read(d, CSR.SATP, mask)
        cycle(d, Inputs(adr = CSR.SATP, word = hex("80000001"), write = true))
        read(d, CSR.SATP, hex("80000001"))
        for (word <- Seq(hex("7fffffff"), hex("60000001"), BigInt(0))) {
          cycle(d, Inputs(adr = CSR.SATP, word = word, write = true))
          read(d, CSR.SATP, 0)
        }
      }
    }
  }

  it should "let M access compare halves without STCE or TM while protecting S access" in {
    test(new CSRSupervisorHarness(SupervisorParams(stc = true))) { d =>
      boot(d)
      cycle(d, Inputs(adr = CSR.STIMECMP, word = hex("89abcdef"), write = true,
        mode = Privilege.MACHINE, stce = false, tm = false))
      cycle(d, Inputs(adr = CSR.STIMECMPH, word = hex("01234567"), write = true,
        mode = Privilege.MACHINE, stce = false, tm = false))
      d.io.STIMECMP_REGW.expect(hex("0123456789abcdef").U)
      for ((stce, tm) <- Seq((false, false), (false, true), (true, false))) {
        for (adr <- Seq(CSR.STIMECMP, CSR.STIMECMPH)) {
          cycle(d, Inputs(adr = adr, word = 0, write = true, stce = stce, tm = tm))
          read(d, adr, 0, allowed = false)
          d.io.STIMECMP_REGW.expect(hex("0123456789abcdef").U)
        }
      }
      cycle(d, Inputs(adr = CSR.STIMECMP, word = 0x76543210L, write = true))
      d.io.STIMECMP_REGW.expect(hex("0123456776543210").U)
      read(d, CSR.STIMECMPH, hex("01234567"))
    }
  }

  it should "compare all 64 bits unsigned and expose raw pending regardless of gates" in {
    test(new CSRSupervisorHarness(SupervisorParams(stc = true))) { d =>
      boot(d)
      val rows = Seq((BigInt(0), BigInt(0), true), (BigInt(1), BigInt(0), false),
        (BigInt(1), BigInt(1), true), (BigInt(1), BigInt(2), true),
        (hex("8000000000000000"), hex("7fffffffffffffff"), false),
        (hex("7fffffffffffffff"), hex("8000000000000000"), true),
        (full64, full64 - 1, false), (full64, full64, true))
      for ((compare, time, pending) <- rows) {
        cycle(d, Inputs(adr = CSR.STIMECMP, word = compare & full, write = true,
          mode = Privilege.MACHINE))
        cycle(d, Inputs(adr = CSR.STIMECMPH, word = compare >> 32, write = true,
          mode = Privilege.MACHINE))
        drive(d, Inputs(time = time, valid = false, stall = true, flush = true,
          mode = Privilege.USER, stce = false, tm = false))
        d.io.STimerInt.expect(pending.B)
        d.io.STIMECMP_REGW.expect(compare.U)
      }
    }
  }

  it should "ignore every event and input when supervisor support is absent" in {
    test(new CSRSupervisorHarness(SupervisorParams(s = false))) { d =>
      boot(d)
      for (adr <- base ++ Seq(CSR.STIMECMP, CSR.STIMECMPH)) {
        cycle(d, Inputs(adr = adr, word = full, write = true, trap = true,
          epc = full, cause = 63, tval = full, time = full64,
          status = full, sip = 0xfff, sie = 0xfff, sw = 0xfff))
        read(d, adr, 0, exists = false, allowed = false)
        checkState(d)
        strobes(d)
        d.io.STimerInt.expect(false.B)
      }
    }
  }

  private def csr(adr: Int, funct: Int, rs: Int = 1): BigInt =
    (BigInt(adr) << 20) | (BigInt(rs) << 15) | (BigInt(funct) << 12) | 0xf3
  private def accessDrive(d: SupervisorAccessHarness, instr: BigInt, src: BigInt = 0,
                          mode: Int = Privilege.MACHINE, mt: Boolean = false,
                            st: Boolean = false,
                          ext: Boolean = false): Unit = {
    d.io.Instr.poke(instr.U)
    d.io.Src.poke(src.U)
    d.io.Mode.poke(mode.U)
    d.io.Valid.poke(true.B)
    d.io.Stall.poke(false.B)
    d.io.Flush.poke(false.B)
    d.io.MTrap.poke(mt.B)
    d.io.STrap.poke(st.B)
    d.io.Time.poke(5.U)
    d.io.SExt.poke(ext.B)
  }

  it should "connect five real modules with shared views controls and trap isolation" in {
    test(new SupervisorAccessHarness) { d =>
      accessDrive(d, 0)
      d.reset.poke(true.B)
      d.clock.step()
      d.reset.poke(false.B)
      for ((adr, word) <- Seq((CSR.MIE, 0xaaa), (CSR.MIDELEG, 0x222), (CSR.SSTATUS, 2),
        (CSR.SSCRATCH, 0xabcd))) {
        accessDrive(d, csr(adr, CSRFunction.RW), word)
        d.io.Illegal.expect(false.B)
        d.clock.step()
      }
      d.io.SIE.expect(0x222.U)
      d.io.Status.expect(2.U)
      accessDrive(d, csr(CSR.SIP, CSRFunction.RSI, rs = 2), mode = Privilege.SUPERVISOR,
        ext = true)
      d.io.Result.expect(0x200.U)
      d.clock.step()
      d.io.MIPSoftware.expect(2.U)
      accessDrive(d, csr(CSR.SIP, CSRFunction.RCI, rs = 2), mode = Privilege.SUPERVISOR,
        ext = true)
      d.io.Result.expect(0x202.U)
      d.clock.step()
      d.io.MIPSoftware.expect(0.U)
      accessDrive(d, csr(CSR.SIP, CSRFunction.RS, rs = 0), mode = Privilege.SUPERVISOR)
      d.io.Result.expect(0.U)
      // S被STCE/TM拦截时，M仍可配置比较器。
      accessDrive(d, csr(CSR.STIMECMP, CSRFunction.RW), src = 9, mode = Privilege.SUPERVISOR)
      d.io.Illegal.expect(true.B)
      d.clock.step()
      accessDrive(d, csr(CSR.STIMECMP, CSRFunction.RW), src = 9)
      d.io.Illegal.expect(false.B)
      d.clock.step()
      for ((adr, word) <- Seq((CSR.MCOUNTEREN, BigInt(2)), (CSR.MENVCFGH, hex("80000000")))) {
        accessDrive(d, csr(adr, CSRFunction.RW), word)
        d.clock.step()
      }
      accessDrive(d, csr(CSR.STIMECMP, CSRFunction.RW), src = 4, mode = Privilege.SUPERVISOR)
      d.io.Illegal.expect(false.B)
      d.clock.step()
      d.io.Compare.expect(4.U)
      d.io.SIP.expect(0x020.U)
      accessDrive(d, csr(CSR.MSTATUS, CSRFunction.RW), src = 0x100002)
      d.clock.step()
      accessDrive(d, csr(CSR.SATP, CSRFunction.RS, rs = 0), mode = Privilege.SUPERVISOR)
      d.io.Illegal.expect(true.B)
      accessDrive(d, csr(CSR.SATP, CSRFunction.RW), src = full)
      d.io.Illegal.expect(false.B)
      d.clock.step()
      d.io.Satp.expect(full.U)
      accessDrive(d, csr(CSR.SATP, CSRFunction.RS, rs = 0), mode = Privilege.USER)
      d.io.Illegal.expect(true.B)
      accessDrive(d, csr(CSR.MSTATUS, CSRFunction.RW), src = full, mode = Privilege.USER,
        st = true)
      d.clock.step()
      d.io.MEPC.expect(0.U)
      d.io.SEPC.expect(0x804.U)
      d.io.SCause.expect(hex("80000005").U)
      d.io.STval.expect(0xcafe.U)
      d.io.Status.expect(0x100020.U)
      d.io.SScratch.expect(0xabcd.U)
      accessDrive(d, csr(CSR.SSCRATCH, CSRFunction.RW), src = full,
        mode = Privilege.SUPERVISOR, mt = true)
      d.clock.step()
      d.io.MEPC.expect(0x804.U)
      d.io.SScratch.expect(0xabcd.U)
      d.io.SEPC.expect(0x804.U)
    }
  }

  it should "match a seeded per-field model across five configurations" in {
    for (p <- Seq(SupervisorParams(s = false), SupervisorParams(),
         SupervisorParams(vm = true, vector = false, hpm = 3, asid = 4),
         SupervisorParams(stc = true), SupervisorParams(vm = true, stc = true,
           hpm = 29, asid = 9))) {
      test(new CSRSupervisorHarness(p)) { d =>
        boot(d)
        val model = new Model(p)
        val random = new Random(0x8e712026L)
        val all = base ++ Seq(CSR.STIMECMP, CSR.STIMECMPH, CSR.MSTATUS, CSR.MCYCLE, 0xfff)
        for (i <- 0 until 512) {
          val a = Inputs(adr = all(random.nextInt(all.size)), word = BigInt(32, random),
            write = random.nextBoolean(), trap = i % 17 == 0, epc = BigInt(32, random),
            cause = random.nextInt(64), tval = BigInt(32, random), mode = random.nextInt(4),
            valid = i % 13 != 0, stall = i % 19 == 0, flush = i % 23 == 0,
            tvm = random.nextBoolean(), stce = random.nextBoolean(), tm = random.nextBoolean(),
            time = BigInt(64, random), status = BigInt(32, random),
            sip = random.nextInt(4096), sie = random.nextInt(4096), sw = random.nextInt(4096))
          val resetting = i % 113 == 0
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

  it should "generate stable 38-port synchronous RTL including a disabled S configuration" in {
    val in = Map("ValidM" -> 1, "StallW" -> 1, "FlushM" -> 1, "CSRWriteM" -> 1,
      "STrapM" -> 1, "CSRAdrM" -> 12, "CSRWriteValM" -> 32, "NextEPCM" -> 32,
      "NextCauseM" -> 6, "NextXtvalM" -> 32, "PrivilegeModeW" -> 2, "STATUS_TVM" -> 1,
      "ENVCFG_STCE" -> 1, "MCOUNTEREN_TM" -> 1, "TIME_REGW" -> 64,
      "SSTATUS_REGW" -> 32, "SIP_REGW" -> 12, "SIE_REGW" -> 12,
      "SIP_REGW_writeable" -> 12)
    val out = Map("CSRSExistsM" -> 1, "CSRSAccessAllowedM" -> 1,
      "CSRSReadValM" -> 32, "CSRSModifyValM" -> 32,
      "STVEC_REGW" -> 32, "SEPC_REGW" -> 32, "SCAUSE_REGW" -> 32, "STVAL_REGW" -> 32,
      "SSCRATCH_REGW" -> 32, "SCOUNTEREN_REGW" -> 32, "SATP_REGW" -> 32,
      "SENVCFG_REGW" -> 32, "STIMECMP_REGW" -> 64, "STimerInt" -> 1,
      "WriteSSTATUSM" -> 1, "WriteSIEM" -> 1, "WriteSIPM" -> 1)
    for (p <- Seq(SupervisorParams(), SupervisorParams(s = false),
         SupervisorParams(vm = true, vector = false, hpm = 3, asid = 4),
         SupervisorParams(stc = true), SupervisorParams(vm = true, stc = true,
           hpm = 29, asid = 9))) {
      val rtl = (new ChiselStage).emitVerilog(
        new CSRSupervisor(p.s, p.vm, p.stc, p.vector, p.hpm, p.asid),
        Array("--target-dir", Files.createTempDirectory("csr-supervisor-rtl-").toString))
      val pattern = "(?m)^\\s*(input|output)\\s+(?:\\[(\\d+):0\\]\\s+)?(\\w+)".r
      val ports = pattern.findAllMatchIn(rtl).map { m =>
        m.group(3) -> (m.group(1), Option(m.group(2)).map(_.toInt + 1).getOrElse(1))
      }.toMap
      val wanted = in.map { case (n, w) => s"io_$n" -> ("input", w) } ++
        out.map { case (n, w) => s"io_$n" -> ("output", w) } ++
        Map("clk" -> ("input", 1), "reset" -> ("input", 1))
      assert(ports == wanted)
      if (p.s) assert(rtl.contains("always @(posedge clk)"))
      assert(!rtl.contains("posedge reset") && !rtl.contains("negedge"))
    }
  }

  it should "reject inconsistent VM Sstc ASID and HPM parameters" in {
    for (p <- Seq(SupervisorParams(s = false, vm = true),
         SupervisorParams(s = false, stc = true), SupervisorParams(hpm = -1),
         SupervisorParams(hpm = 30), SupervisorParams(vm = true, asid = -1),
         SupervisorParams(vm = true, asid = 10), SupervisorParams(asid = 1))) {
      intercept[IllegalArgumentException] {
        (new ChiselStage).emitChirrtl(
          new CSRSupervisor(p.s, p.vm, p.stc, p.vector, p.hpm, p.asid))
      }
    }
  }
}
