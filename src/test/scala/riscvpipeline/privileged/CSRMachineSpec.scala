package riscvpipeline.privileged

import riscvpipeline.TestEncoding.{CSR, CSRFunction, Cause, Mask, Opcode, Privilege}
import chisel3._
import chisel3.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.Files
import scala.language.reflectiveCalls
import scala.util.Random

private case class MachineParams(s: Boolean = true, vm: Boolean = false,
  stc: Boolean = false, vector: Boolean = true, hpm: Int = 0, hart: BigInt = 0)

private class CSRMachineHarness(p: MachineParams = MachineParams()) extends Module {
  val io = IO(new CSRMachineIO)
  val dut = Module(new CSRMachine(p.s, p.vm, p.stc, p.vector, p.hpm, p.hart))
  dut.clk := clock
  dut.reset := reset.asBool
  io <> dut.io
}

// 四个真实模块的测试连接，不改变生产CPU；各模块共享提交门控。
private class MachineAccessHarness extends Module {
  val io = IO(new Bundle {
    val Instr = Input(UInt(32.W))
    val Src = Input(UInt(32.W))
    val Mode = Input(UInt(2.W))
    val Valid = Input(Bool())
    val Stall = Input(Bool())
    val Flush = Input(Bool())
    val Trap = Input(Bool())
    val EPC = Input(UInt(32.W))
    val Cause = Input(UInt(6.W))
    val Tval = Input(UInt(32.W))
    val SExt = Input(Bool())
    val Result = Output(UInt(32.W))
    val Illegal = Output(Bool())
    val MIPSoftware = Output(UInt(12.W))
    val MIP = Output(UInt(12.W))
    val MIE = Output(UInt(12.W))
    val SIP = Output(UInt(12.W))
    val SIE = Output(UInt(12.W))
    val Status = Output(UInt(32.W))
    val EPCOut = Output(UInt(32.W))
    val CauseOut = Output(UInt(32.W))
    val TvalOut = Output(UInt(32.W))
    val Scratch = Output(UInt(32.W))
  })
  val machine = Module(new CSRMachine)
  val access = Module(new CSRAccess)
  val status = Module(new CSRStatus)
  val interrupts = Module(new CSRInterrupt)
  machine.clk := clock
  machine.reset := reset.asBool
  status.clk := clock
  status.reset := reset.asBool
  interrupts.clk := clock
  interrupts.reset := reset.asBool
  access.io.InstrM := io.Instr
  access.io.ValidM := io.Valid
  access.io.PrivilegeModeW := io.Mode
  access.io.SrcAM := io.Src
  access.io.CSRReadValM := machine.io.CSRMReadValM
  access.io.CSRModifyValM := machine.io.CSRMModifyValM
  access.io.CSRExistsM := machine.io.CSRMExistsM
  access.io.CSRAccessAllowedM := true.B
  machine.io.CSRAdrM := access.io.CSRAdrM
  machine.io.CSRWriteM := access.io.CSRWriteM
  machine.io.CSRWriteValM := access.io.CSRWriteValM
  machine.io.ValidM := io.Valid
  machine.io.StallW := io.Stall
  machine.io.FlushM := io.Flush
  machine.io.MTrapM := io.Trap
  machine.io.NextEPCM := io.EPC
  machine.io.NextCauseM := io.Cause
  machine.io.NextXtvalM := io.Tval
  machine.io.MSTATUS_REGW := status.io.MSTATUS_REGW
  machine.io.MSTATUSH_REGW := status.io.MSTATUSH_REGW
  machine.io.MIP_REGW := interrupts.io.MIP_REGW
  machine.io.MIE_REGW := interrupts.io.MIE_REGW
  machine.io.MIP_REGW_writeable := interrupts.io.MIP_REGW_writeable
  status.io.ValidM := io.Valid
  status.io.StallW := io.Stall
  status.io.FlushM := io.Flush
  status.io.CSRWriteValM := access.io.CSRWriteValM
  status.io.WriteMSTATUSM := machine.io.WriteMSTATUSM
  status.io.WriteMSTATUSHM := machine.io.WriteMSTATUSHM
  status.io.WriteSSTATUSM := false.B
  status.io.TrapM := io.Trap
  status.io.PrivilegeModeW := io.Mode
  status.io.NextPrivilegeModeM := 3.U(2.W)
  status.io.mretM := false.B
  status.io.sretM := false.B
  status.io.SelHPTW := false.B
  interrupts.io.ValidM := io.Valid
  interrupts.io.StallW := io.Stall
  interrupts.io.FlushM := io.Flush
  interrupts.io.CSRWriteValM := access.io.CSRWriteValM
  interrupts.io.WriteMIEM := machine.io.WriteMIEM
  interrupts.io.WriteMIPM := machine.io.WriteMIPM
  interrupts.io.WriteSIEM := false.B
  interrupts.io.WriteSIPM := false.B
  interrupts.io.MIDELEG_REGW := machine.io.MIDELEG_REGW
  interrupts.io.MExtInt := false.B
  interrupts.io.SExtInt := io.SExt
  interrupts.io.MTimerInt := false.B
  interrupts.io.STimerInt := false.B
  interrupts.io.MSwInt := false.B
  interrupts.io.ENVCFG_STCE := machine.io.MENVCFG_REGW(63)
  io.Result := access.io.CSRResultM
  io.Illegal := access.io.IllegalCSRAccessM
  io.MIPSoftware := interrupts.io.MIP_REGW_writeable
  io.MIP := interrupts.io.MIP_REGW
  io.MIE := interrupts.io.MIE_REGW
  io.SIP := interrupts.io.SIP_REGW
  io.SIE := interrupts.io.SIE_REGW
  io.Status := status.io.MSTATUS_REGW
  io.EPCOut := machine.io.MEPC_REGW
  io.CauseOut := machine.io.MCAUSE_REGW
  io.TvalOut := machine.io.MTVAL_REGW
  io.Scratch := machine.io.MSCRATCH_REGW
}

class CSRMachineSpec extends AnyFlatSpec with ChiselScalatestTester {
  private def hex(s: String): BigInt = BigInt(s, 16)
  private val full = hex("ffffffff")
  private val owned = Seq(CSR.MTVEC, CSR.MEDELEG, CSR.MIDELEG, CSR.MSCRATCH, CSR.MEPC,
    CSR.MCAUSE, CSR.MTVAL,
    CSR.MCOUNTEREN, CSR.MCOUNTINHIBIT, CSR.MENVCFG, CSR.MENVCFGH)
  private val addresses = Seq(CSR.MSTATUS, CSR.MISA, CSR.MEDELEG, CSR.MIDELEG, CSR.MIE,
    CSR.MTVEC, CSR.MCOUNTEREN,
    CSR.MENVCFG, CSR.MSTATUSH, CSR.MENVCFGH, CSR.MCOUNTINHIBIT, CSR.MSCRATCH, CSR.MEPC,
      CSR.MCAUSE, CSR.MTVAL, CSR.MIP) ++
    (CSR.MVENDORID to CSR.MCONFIGPTR)
  private case class Inputs(adr: Int = CSR.MSCRATCH, word: BigInt = 0,
    write: Boolean = false, trap: Boolean = false, epc: BigInt = 0,
    cause: Int = 0, tval: BigInt = 0, valid: Boolean = true,
    stall: Boolean = false, flush: Boolean = false,
    status: BigInt = 0, high: BigInt = 0, mip: Int = 0, mie: Int = 0, sw: Int = 0)
  private def drive(d: CSRMachineHarness, a: Inputs): Unit = {
    d.io.CSRAdrM.poke(a.adr.U)
    d.io.CSRWriteValM.poke(a.word.U)
    d.io.CSRWriteM.poke(a.write.B)
    d.io.MTrapM.poke(a.trap.B)
    d.io.NextEPCM.poke(a.epc.U)
    d.io.NextCauseM.poke(a.cause.U)
    d.io.NextXtvalM.poke(a.tval.U)
    d.io.ValidM.poke(a.valid.B)
    d.io.StallW.poke(a.stall.B)
    d.io.FlushM.poke(a.flush.B)
    d.io.MSTATUS_REGW.poke(a.status.U)
    d.io.MSTATUSH_REGW.poke(a.high.U)
    d.io.MIP_REGW.poke(a.mip.U)
    d.io.MIE_REGW.poke(a.mie.U)
    d.io.MIP_REGW_writeable.poke(a.sw.U)
  }
  private def checkState(d: CSRMachineHarness, v: Map[Int, BigInt] = Map.empty): Unit = {
    for ((address, port) <- Seq(CSR.MTVEC -> d.io.MTVEC_REGW, CSR.MEDELEG -> d.io.MEDELEG_REGW,
         CSR.MIDELEG -> d.io.MIDELEG_REGW, CSR.MSCRATCH -> d.io.MSCRATCH_REGW,
         CSR.MEPC -> d.io.MEPC_REGW, CSR.MCAUSE -> d.io.MCAUSE_REGW,
           CSR.MTVAL -> d.io.MTVAL_REGW,
         CSR.MCOUNTEREN -> d.io.MCOUNTEREN_REGW,
           CSR.MCOUNTINHIBIT -> d.io.MCOUNTINHIBIT_REGW)) {
      port.expect(v.getOrElse(address, BigInt(0)).U)
    }
    val env = (v.getOrElse(CSR.MENVCFGH, BigInt(0)) << 32) + v.getOrElse(CSR.MENVCFG,
      BigInt(0))
    d.io.MENVCFG_REGW.expect(env.U)
  }
  private def strobes(d: CSRMachineHarness, status: Boolean = false,
                      high: Boolean = false, mie: Boolean = false,
                      mip: Boolean = false): Unit = {
    d.io.WriteMSTATUSM.expect(status.B)
    d.io.WriteMSTATUSHM.expect(high.B)
    d.io.WriteMIEM.expect(mie.B)
    d.io.WriteMIPM.expect(mip.B)
  }
  private def boot(d: CSRMachineHarness): Unit = {
    drive(d, Inputs(valid = false))
    d.reset.poke(true.B)
    d.clock.step()
    d.reset.poke(false.B)
    checkState(d)
  }
  private def cycle(d: CSRMachineHarness, a: Inputs): Unit = {
    drive(d, a)
    d.clock.step()
  }
  private def read(d: CSRMachineHarness, adr: Int, want: BigInt,
                   exists: Boolean = true, modify: Option[BigInt] = None): Unit = {
    d.io.CSRAdrM.poke(adr.U)
    d.io.CSRMExistsM.expect(exists.B)
    d.io.CSRMReadValM.expect(want.U)
    d.io.CSRMModifyValM.expect(modify.getOrElse(want).U)
  }

  // 字段模型使用地址字典、逐位允许集和算术对齐；不调用RTL的掩码/构造函数。
  private class Model(p: MachineParams) {
    var state: Map[Int, BigInt] = owned.map(_ -> BigInt(0)).toMap
    private def select(word: BigInt, allowed: Seq[Int]): BigInt =
      allowed.filter(word.testBit).map(i => BigInt(1) << i).sum
    def exists(adr: Int): Boolean = addresses.contains(adr) &&
      (p.s || (adr != CSR.MEDELEG && adr != CSR.MIDELEG))
    def value(a: Inputs): BigInt = a.adr match {
      case CSR.MSTATUS => a.status
      case CSR.MSTATUSH => a.high
      case CSR.MIE => BigInt(a.mie)
      case CSR.MIP => BigInt(a.mip)
      case CSR.MISA => BigInt(1) << 30 | BigInt(1) << 20 | BigInt(1) << 8 |
        (if (p.s) BigInt(1) << 18 else BigInt(0))
      case CSR.MHARTID => p.hart
      case adr if exists(adr) => state.getOrElse(adr, BigInt(0))
      case _ => BigInt(0)
    }
    def expect(d: CSRMachineHarness, a: Inputs): Unit = {
      checkState(d, state)
      read(d, a.adr, value(a), exists(a.adr),
        if (a.adr == CSR.MIP) Some(BigInt(a.sw)) else None)
      val active = a.valid && !a.stall && !a.flush && !a.trap && a.write
      strobes(d, active && a.adr == CSR.MSTATUS, active && a.adr == CSR.MSTATUSH,
        active && a.adr == CSR.MIE, active && a.adr == CSR.MIP)
    }
    def update(a: Inputs, resetting: Boolean): Unit = {
      if (resetting) state = state.view.mapValues(_ => BigInt(0)).toMap
      else if (a.valid && !a.stall && !a.flush) {
        if (a.trap) state ++= Map(CSR.MEPC -> (a.epc / 4 * 4),
          CSR.MCAUSE -> (BigInt(a.cause % Cause.INPUT_INTERRUPT_FLAG) +
            (if (a.cause >= Cause.INPUT_INTERRUPT_FLAG) BigInt(1) << 31 else BigInt(0))),
          CSR.MTVAL -> a.tval)
        else if (a.write && exists(a.adr) && owned.contains(a.adr)) {
          val next = a.adr match {
            case CSR.MTVEC => if (p.vector && a.word.testBit(0)) a.word / 64 * 64 + 1
              else a.word / 4 * 4
            case CSR.MEPC => a.word / 4 * 4
            case CSR.MCAUSE => select(a.word, Seq(31) ++ (0 to 4))
            case CSR.MEDELEG => select(a.word, (0 to 9) ++
              (if (p.vm) Seq(12, 13, 15) else Seq.empty))
            case CSR.MIDELEG => select(a.word, Seq(1, 5, 9))
            case CSR.MCOUNTEREN => select(a.word, (0 to 2) ++ (3 until p.hpm + 3))
            case CSR.MCOUNTINHIBIT => select(a.word, Seq(0, 2) ++ (3 until p.hpm + 3))
            case CSR.MENVCFG => if (p.vm && a.word.testBit(0)) BigInt(1) else BigInt(0)
            case CSR.MENVCFGH => if (p.stc &&
              a.word.testBit(31)) BigInt(1) << 31 else BigInt(0)
            case _ => a.word
          }
          state += a.adr -> next
        }
      }
    }
  }

  behavior of "CSRMachine"

  it should "reset synchronously without gating forwarded writes with reset" in {
    test(new CSRMachineHarness) { d =>
      boot(d)
      cycle(d, Inputs(word = full, write = true))
      d.reset.poke(true.B)
      checkState(d, Map(CSR.MSCRATCH -> full))
      for (address <- Seq(CSR.MSTATUS, CSR.MSTATUSH, CSR.MIE, CSR.MIP)) {
        drive(d, Inputs(adr = address, word = full, write = true))
        strobes(d, status = address == CSR.MSTATUS, high = address == CSR.MSTATUSH,
          mie = address == CSR.MIE, mip = address == CSR.MIP)
      }
      drive(d, Inputs(word = full, write = true))
      strobes(d)
      d.clock.step()
      checkState(d)
      d.reset.poke(false.B)
    }
  }

  it should "decode all known machine addresses and reject unrelated banks" in {
    for (s <- Seq(false, true)) {
      test(new CSRMachineHarness(MachineParams(s = s))) { d =>
        boot(d)
        for (address <- 0 until 4096) {
          d.io.CSRAdrM.poke(address.U)
          val exists = addresses.contains(address) &&
            (s || (address != CSR.MEDELEG && address != CSR.MIDELEG))
          d.io.CSRMExistsM.expect(exists.B)
          if (!exists) {
            d.io.CSRMReadValM.expect(0.U)
            d.io.CSRMModifyValM.expect(0.U)
          }
        }
      }
    }
  }

  it should "report fixed RV32 I U S capabilities and local identity values" in {
    for ((s, want) <- Seq((false, hex("40100100")), (true, hex("40140100")))) {
      test(new CSRMachineHarness(MachineParams(s = s, hart = full))) { d =>
        boot(d)
        read(d, CSR.MISA, want)
        cycle(d, Inputs(adr = CSR.MISA, write = true, word = 0))
        read(d, CSR.MISA, want)
        for (address <- Seq(CSR.MVENDORID, CSR.MARCHID, CSR.MIMPID, CSR.MCONFIGPTR)) read(d,
          address, 0)
        read(d, CSR.MHARTID, full)
        for (address <- CSR.MVENDORID to CSR.MCONFIGPTR) {
          cycle(d, Inputs(adr = address, write = true, word = 0))
          strobes(d)
        }
        read(d, CSR.MHARTID, full)
        checkState(d)
      }
    }
  }

  it should "forward old status and interrupts with a software-only MIP modify value" in {
    test(new CSRMachineHarness) { d =>
      boot(d)
      drive(d, Inputs(status = hex("89abcdef"), high = hex("12345678"),
        mip = 0xa88, mie = 0xaaa, sw = 0x020, valid = false, stall = true, flush = true))
      read(d, CSR.MSTATUS, hex("89abcdef"))
      read(d, CSR.MSTATUSH, hex("12345678"))
      read(d, CSR.MIE, 0xaaa)
      read(d, CSR.MIP, 0xa88, modify = Some(BigInt(0x020)))
      strobes(d)
    }
  }

  it should "emit only the selected allowed leaf write strobe" in {
    test(new CSRMachineHarness) { d =>
      boot(d)
      for (address <- addresses ++ Seq(CSR.SSTATUS, CSR.PMPCFG0, CSR.MCYCLE)) {
        drive(d, Inputs(adr = address, write = true))
        strobes(d, address == CSR.MSTATUS, address == CSR.MSTATUSH,
          address == CSR.MIE, address == CSR.MIP)
      }
      for (address <- Seq(CSR.MSTATUS, CSR.MSTATUSH, CSR.MIE, CSR.MIP)) {
        for (a <- Seq(Inputs(adr = address), Inputs(adr = address, write = true,
             valid = false), Inputs(adr = address, write = true, stall = true),
             Inputs(adr = address, write = true, flush = true),
             Inputs(adr = address, write = true, trap = true))) {
          drive(d, a)
          strobes(d)
        }
        drive(d, Inputs(adr = address, write = true))
        d.reset.poke(true.B)
        strobes(d, address == CSR.MSTATUS, address == CSR.MSTATUSH,
          address == CSR.MIE, address == CSR.MIP)
        d.reset.poke(false.B)
      }
    }
  }

  it should "preserve full scratch and tval data but align EPC and restrict cause format" in {
    test(new CSRMachineHarness) { d =>
      boot(d)
      val rows = Seq((CSR.MSCRATCH, hex("89abcdef"), hex("89abcdef")),
        (CSR.MTVAL, full, full), (CSR.MEPC, hex("80000003"), hex("80000000")),
        (CSR.MCAUSE, full, hex("8000001f")), (CSR.MCAUSE, hex("7fffffe7"), BigInt(7)))
      for ((adr, word, want) <- rows) {
        cycle(d, Inputs(adr = adr, write = true, word = word))
        read(d, adr, want)
      }
      for (low <- 0 to 3) {
        cycle(d, Inputs(adr = CSR.MEPC, write = true, word = 0x12345678L + low))
        read(d, CSR.MEPC, hex("12345678"))
      }
    }
  }

  it should "legalize all TVEC mode encodings and enforce direct or vectored alignment" in {
    for (vector <- Seq(false, true)) {
      test(new CSRMachineHarness(MachineParams(vector = vector))) { d =>
        boot(d)
        for ((word, vectored, direct) <- Seq(
             (hex("8000013c"), hex("8000013c"), hex("8000013c")),
             (hex("8000013d"), hex("80000101"), hex("8000013c")),
             (hex("8000013e"), hex("8000013c"), hex("8000013c")),
             (hex("8000013f"), hex("80000101"), hex("8000013c")),
             (full, hex("ffffffc1"), hex("fffffffc")))) {
          cycle(d, Inputs(adr = CSR.MTVEC, write = true, word = word))
          read(d, CSR.MTVEC, if (vector) vectored else direct)
        }
      }
    }
  }

  it should "atomically record machine traps and prevent simultaneous software updates" in {
    test(new CSRMachineHarness) { d =>
      boot(d)
      cycle(d, Inputs(word = hex("12345678"), write = true))
      for (address <- owned ++ Seq(CSR.MSTATUS, CSR.MSTATUSH, CSR.MIE, CSR.MIP, 0xfff)) {
        cycle(d, Inputs(adr = address, word = full, write = true, trap = true,
          epc = hex("80001237"), cause = 39, tval = hex("a5a5f00d")))
        checkState(d, Map(CSR.MSCRATCH -> hex("12345678"), CSR.MEPC -> hex("80001234"),
          CSR.MCAUSE -> hex("80000007"), CSR.MTVAL -> hex("a5a5f00d")))
        strobes(d)
      }
      cycle(d, Inputs(trap = true, epc = 0x004, cause = 2, tval = 0))
      checkState(d, Map(CSR.MSCRATCH -> hex("12345678"), CSR.MEPC -> BigInt(4),
        CSR.MCAUSE -> BigInt(2)))
    }
  }

  it should "map every six-bit trap cause to the RV32 interrupt flag and five-bit cause" in {
    test(new CSRMachineHarness) { d =>
      boot(d)
      for (cause <- 0 until 64) {
        cycle(d, Inputs(trap = true, cause = cause))
        val want = BigInt(cause % Cause.INPUT_INTERRUPT_FLAG) + (if (cause >= Cause.INPUT_INTERRUPT_FLAG) BigInt(1) << 31 else BigInt(0))
        read(d, CSR.MCAUSE, want)
      }
    }
  }

  it should "hold all software and trap state under invalid stall and flush gates" in {
    test(new CSRMachineHarness) { d =>
      boot(d)
      cycle(d, Inputs(adr = CSR.MEPC, write = true, word = 0x104))
      for (valid <- Seq(false, true); stall <- Seq(false, true);
           flush <- Seq(false, true) if !valid || stall || flush;
           address <- owned; trap <- Seq(false, true)) {
        cycle(d, Inputs(adr = address, word = full, write = true, trap = trap,
          epc = full, cause = 63, tval = full, valid = valid, stall = stall, flush = flush))
        checkState(d, Map(CSR.MEPC -> BigInt(0x104)))
        strobes(d)
      }
    }
  }

  it should "mask delegation by supported supervisor and virtual memory exceptions" in {
    for ((p, mask) <- Seq((MachineParams(s = false), 0), (MachineParams(),
      Mask.BASE_EXCEPTION_DELEGATION),
         (MachineParams(vm = true), Mask.VM_EXCEPTION_DELEGATION))) {
      test(new CSRMachineHarness(p)) { d =>
        boot(d)
        cycle(d, Inputs(adr = CSR.MEDELEG, write = true, word = full))
        read(d, CSR.MEDELEG, mask, exists = p.s)
        cycle(d, Inputs(adr = CSR.MIDELEG, write = true, word = full))
        read(d, CSR.MIDELEG, if (p.s) 0x222 else 0, exists = p.s)
        cycle(d, Inputs(adr = CSR.MEDELEG, write = true, word = 0x800))
        read(d, CSR.MEDELEG, 0, exists = p.s)
        cycle(d, Inputs(adr = CSR.MIDELEG, write = true, word = 0x888))
        read(d, CSR.MIDELEG, 0, exists = p.s)
      }
    }
  }

  it should "mask counter access and inhibit fields without allowing TM inhibition" in {
    for ((hpm, access, inhibit) <- Seq((0, BigInt(7), BigInt(5)),
         (1, BigInt(15), BigInt(13)), (3, BigInt(63), BigInt(61)),
         (29, full, hex("fffffffd")))) {
      test(new CSRMachineHarness(MachineParams(hpm = hpm))) { d =>
        boot(d)
        cycle(d, Inputs(adr = CSR.MCOUNTEREN, write = true, word = full))
        read(d, CSR.MCOUNTEREN, access)
        cycle(d, Inputs(adr = CSR.MCOUNTINHIBIT, write = true, word = full))
        read(d, CSR.MCOUNTINHIBIT, inhibit)
        cycle(d, Inputs(adr = CSR.MCOUNTINHIBIT, write = true, word = 2))
        read(d, CSR.MCOUNTINHIBIT, 0)
        read(d, CSR.MCOUNTEREN, access)
      }
    }
  }

  it should "update environment halves independently and zero unsupported feature fields" in {
    for (vm <- Seq(false, true); stc <- Seq(false, true)) {
      test(new CSRMachineHarness(MachineParams(vm = vm, stc = stc))) { d =>
        boot(d)
        cycle(d, Inputs(adr = CSR.MENVCFG, write = true, word = full))
        read(d, CSR.MENVCFG, if (vm) 1 else 0)
        cycle(d, Inputs(adr = CSR.MENVCFGH, write = true, word = full))
        read(d, CSR.MENVCFGH, if (stc) hex("80000000") else 0)
        read(d, CSR.MENVCFG, if (vm) 1 else 0)
        cycle(d, Inputs(adr = CSR.MENVCFG, write = true, word = 0))
        read(d, CSR.MENVCFGH, if (stc) hex("80000000") else 0)
        cycle(d, Inputs(adr = CSR.MENVCFGH, write = true, word = 0))
        d.io.MENVCFG_REGW.expect(0.U)
      }
    }
  }

  private def csr(adr: Int, funct: Int, rs: Int = 1, rd: Int = 1): BigInt =
    (BigInt(adr) << 20) | (BigInt(rs) << 15) | (BigInt(funct) << 12) |
      (BigInt(rd) << 7) | Opcode.SYSTEM
  private def accessDrive(d: MachineAccessHarness, instr: BigInt, src: BigInt = 0,
                          mode: Int = Privilege.MACHINE, valid: Boolean = true,
                            stall: Boolean = false,
                          flush: Boolean = false, trap: Boolean = false,
                          ext: Boolean = false): Unit = {
    d.io.Instr.poke(instr.U)
    d.io.Src.poke(src.U)
    d.io.Mode.poke(mode.U)
    d.io.Valid.poke(valid.B)
    d.io.Stall.poke(stall.B)
    d.io.Flush.poke(flush.B)
    d.io.Trap.poke(trap.B)
    d.io.EPC.poke(0x804.U)
    d.io.Cause.poke(39.U)
    d.io.Tval.poke(0xdead.U)
    d.io.SExt.poke(ext.B)
  }

  it should "connect real access status interrupts and machine delegation" in {
    test(new MachineAccessHarness) { d =>
      accessDrive(d, 0, valid = false)
      d.reset.poke(true.B)
      d.clock.step()
      d.reset.poke(false.B)
      for ((adr, word) <- Seq((CSR.MSTATUS, 8), (CSR.MIE, 0xaaa), (CSR.MIDELEG, 0x222))) {
        accessDrive(d, csr(adr, CSRFunction.RW), word)
        d.io.Result.expect(0.U)
        d.io.Illegal.expect(false.B)
        d.clock.step()
      }
      d.io.Status.expect(8.U)
      d.io.SIE.expect(0x222.U)
      accessDrive(d, csr(CSR.MIP, CSRFunction.RS), src = 2, ext = true)
      d.io.Result.expect(0x200.U)
      d.clock.step()
      d.io.MIPSoftware.expect(2.U)
      d.io.SIP.expect(0x202.U)
      accessDrive(d, csr(CSR.MIP, CSRFunction.RS, rs = 0))
      d.io.Result.expect(2.U)
      for ((adr, mode) <- Seq((CSR.MSCRATCH, 1), (CSR.MTVEC, 0), (CSR.MEPC, 2),
        (CSR.PMPCFG0, 3),
           (CSR.MCYCLE, 3), (CSR.MHARTID, 3))) {
        accessDrive(d, csr(adr, CSRFunction.RW, rd = 0), src = full, mode = mode)
        d.io.Illegal.expect(true.B)
        d.clock.step()
        d.io.Scratch.expect(0.U)
      }
      // 固定misa的写地址合法，字段不变；标识地址的写则异常。
      accessDrive(d, csr(CSR.MISA, CSRFunction.RW), src = full)
      d.io.Illegal.expect(false.B)
      d.io.Result.expect(hex("40140100").U)
      d.clock.step()
      for ((valid, stall, flush) <- Seq((false, false, false),
           (true, true, false), (true, false, true))) {
        accessDrive(d, csr(CSR.MSCRATCH, CSRFunction.RW), src = full, valid = valid,
          stall = stall, flush = flush)
        d.clock.step()
        d.io.Scratch.expect(0.U)
      }
      accessDrive(d, csr(CSR.MIE, CSRFunction.RW), src = 0, trap = true)
      d.clock.step()
      d.io.MIE.expect(0xaaa.U)
      d.io.Status.expect(0x1880.U)
      d.io.EPCOut.expect(0x804.U)
      d.io.CauseOut.expect(hex("80000007").U)
      d.io.TvalOut.expect(0xdead.U)
    }
  }

  it should "match a seeded per-field model across representative configurations" in {
    for (p <- Seq(MachineParams(s = false), MachineParams(),
         MachineParams(vm = true, stc = true, hpm = 29, hart = full),
         MachineParams(vector = false, hpm = 3))) {
      test(new CSRMachineHarness(p)) { d =>
        boot(d)
        val model = new Model(p)
        val random = new Random(0x8d612026L)
        val inputs = addresses ++ Seq(CSR.SSTATUS, CSR.PMPCFG0, CSR.MCYCLE, 0xfff)
        for (i <- 0 until 512) {
          val a = Inputs(adr = inputs(random.nextInt(inputs.size)), word = BigInt(32, random),
            write = random.nextBoolean(), trap = i % 17 == 0, epc = BigInt(32, random),
            cause = random.nextInt(64), tval = BigInt(32, random), valid = i % 13 != 0,
            stall = i % 19 == 0, flush = i % 23 == 0, status = BigInt(32, random),
            high = BigInt(32, random), mip = random.nextInt(4096),
            mie = random.nextInt(4096), sw = random.nextInt(4096))
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

  it should "generate stable 34-port synchronous RV32 RTL across configurations" in {
    val in = Map("ValidM" -> 1, "StallW" -> 1, "FlushM" -> 1, "CSRWriteM" -> 1,
      "MTrapM" -> 1, "CSRAdrM" -> 12, "CSRWriteValM" -> 32, "NextEPCM" -> 32,
      "NextCauseM" -> 6, "NextXtvalM" -> 32, "MSTATUS_REGW" -> 32,
      "MSTATUSH_REGW" -> 32, "MIP_REGW" -> 12, "MIE_REGW" -> 12,
      "MIP_REGW_writeable" -> 12)
    val out = Map("CSRMReadValM" -> 32, "CSRMModifyValM" -> 32, "CSRMExistsM" -> 1,
      "MTVEC_REGW" -> 32, "MEPC_REGW" -> 32, "MCAUSE_REGW" -> 32,
      "MTVAL_REGW" -> 32, "MSCRATCH_REGW" -> 32, "MCOUNTEREN_REGW" -> 32,
      "MCOUNTINHIBIT_REGW" -> 32, "MEDELEG_REGW" -> 16, "MIDELEG_REGW" -> 12,
      "MENVCFG_REGW" -> 64, "WriteMSTATUSM" -> 1, "WriteMSTATUSHM" -> 1,
      "WriteMIEM" -> 1, "WriteMIPM" -> 1)
    for (p <- Seq(MachineParams(), MachineParams(s = false),
         MachineParams(vector = false), MachineParams(vm = true, stc = true, hpm = 29))) {
      val dir = Files.createTempDirectory("csr-machine-rtl-").toString
      val rtl = (new ChiselStage).emitVerilog(
        new CSRMachine(p.s, p.vm, p.stc, p.vector, p.hpm, p.hart),
        Array("--target-dir", dir))
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

  it should "reject inconsistent modes and out-of-range configuration parameters" in {
    for (p <- Seq(MachineParams(s = false, vm = true), MachineParams(s = false, stc = true),
         MachineParams(hpm = -1), MachineParams(hpm = 30), MachineParams(hart = -1),
         MachineParams(hart = BigInt(1) << 32))) {
      intercept[IllegalArgumentException] {
        (new ChiselStage).emitChirrtl(
          new CSRMachine(p.s, p.vm, p.stc, p.vector, p.hpm, p.hart))
      }
    }
  }
}
