package riscvpipeline

import chisel3._
import chiseltest._
import chiseltest.experimental.expose
import org.scalatest.flatspec.AnyFlatSpec
import riscvpipeline.config.CpuConfig
import riscvpipeline.privileged.{InterruptInputs, PrivilegedConfig}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.collection.mutable.ArrayBuffer

/** Tests execute real RV32 instructions; exposed state is confined to this wrapper. */
private[riscvpipeline] final class Chapter8Harness(config: CpuConfig) extends Module {
  val io = IO(new RiscvPipelineIO(config))
  val interrupts = IO(Input(new InterruptInputs))
  val dut = Module(new PrivilegedPipeline(config))
  dut.clk := clock
  dut.reset := reset.asBool
  dut.io <> io
  dut.interrupts := interrupts
  val p = dut.privileged.get
  val Registers = expose(dut.ieu.dp.rf.Registers)
  val PCF = expose(dut.ifu.io.PCF)
  val PCD = expose(dut.ifu.io.PCD)
  val ValidM = expose(dut.ieu.dp.io.ValidM)
  val InstrM = expose(dut.ieu.dp.io.InstrM)
  val PCM = expose(dut.ieu.dp.io.PCM)
  val ValidW = expose(dut.ieu.dp.io.ValidW)
  val RegWriteW = expose(dut.ieu.dp.io.RegWriteW)
  val RdW = expose(dut.ieu.dp.io.RdW)
  val Trap = expose(p.io.TrapM)
  val Interrupt = expose(p.io.InterruptM)
  val Cause = expose(p.io.CauseM)
  val Mode = expose(p.io.PrivilegeModeW)
  val NextMode = expose(p.io.NextPrivilegeModeM)
  val Redirect = expose(p.io.RedirectM)
  val RedirectPC = expose(p.io.RedirectPCM)
  val WFIStall = expose(p.io.WFIStallM)
  val WFITimeout = expose(p.io.WFITimeoutM)
  val Retired = expose(p.io.InstrRetiredM)
  val Instret = expose(p.io.MINSTRET_REGW)
  val Status = expose(p.io.MSTATUS_REGW)
  val StatusHigh = expose(p.csr.status.io.MSTATUSH_REGW)
  val Scratch = expose(p.csr.machine.io.MSCRATCH_REGW)
  val MEPC = expose(p.csr.machine.io.MEPC_REGW)
  val MCause = expose(p.csr.machine.io.MCAUSE_REGW)
  val MTval = expose(p.csr.machine.io.MTVAL_REGW)
  val SEPC = expose(p.csr.supervisor.io.SEPC_REGW)
  val SCause = expose(p.csr.supervisor.io.SCAUSE_REGW)
  val STval = expose(p.csr.supervisor.io.STVAL_REGW)
  val Pending = expose(p.csr.io.MIP_REGW)
}

/** Independent hand encoder and label relocations, requiring no assembler installation. */
private[riscvpipeline] object Chapter8Programs {
  val Nop: BigInt = 0x13
  val Ecall: BigInt = 0x00000073
  val Ebreak: BigInt = 0x00100073
  val Mret: BigInt = 0x30200073
  val Sret: BigInt = 0x10200073
  val Wfi: BigInt = 0x10500073
  val DoneAddress = 512
  val DoneValue = 0x5a
  val TrapLog = 1024
  val Mask: BigInt = BigInt("ffffffff", 16)
  def addi(rd: Int, rs1: Int, imm: Int): BigInt =
    (BigInt(imm & 4095) << 20) | (BigInt(rs1) << 15) | (BigInt(rd) << 7) | 0x13
  def lui(rd: Int, upper: BigInt): BigInt = (upper << 12) | (BigInt(rd) << 7) | 0x37
  def csr(rd: Int, address: Int, source: Int, op: Int = 2): BigInt =
    (BigInt(address) << 20) | (BigInt(source) << 15) | (BigInt(op) << 12) |
      (BigInt(rd) << 7) | 0x73
  def load(rd: Int, rs1: Int, offset: Int = 0, op: Int = 2): BigInt =
    (BigInt(offset & 4095) << 20) | (BigInt(rs1) << 15) | (BigInt(op) << 12) |
      (BigInt(rd) << 7) | 3
  def store(rs: Int, address: Int, op: Int = 2, base: Int = 0): BigInt = {
    val imm = address & 4095
    (BigInt(imm >> 5) << 25) | (BigInt(rs) << 20) | (BigInt(base) << 15) |
      (BigInt(op) << 12) | (BigInt(imm & 31) << 7) | 0x23
  }
  def jal(rd: Int, offset: Int): BigInt = {
    val n = offset & 0x1fffff
    (BigInt((n >> 20) & 1) << 31) | (BigInt((n >> 1) & 1023) << 21) |
      (BigInt((n >> 11) & 1) << 20) | (BigInt((n >> 12) & 255) << 12) |
      (BigInt(rd) << 7) | 0x6f
  }
  def branch(rs1: Int, rs2: Int, offset: Int, op: Int = 0): BigInt = {
    val n = offset & 8191
    (BigInt((n >> 12) & 1) << 31) | (BigInt((n >> 5) & 63) << 25) |
      (BigInt(rs2) << 20) | (BigInt(rs1) << 15) | (BigInt(op) << 12) |
      (BigInt((n >> 1) & 15) << 8) | (BigInt((n >> 11) & 1) << 7) | 0x63
  }
  def jalr(rd: Int, rs: Int, offset: Int = 0): BigInt =
    (BigInt(offset & 4095) << 20) | (BigInt(rs) << 15) | (BigInt(rd) << 7) | 0x67

  final class Program(val base: Int = 0) {
    private val code = ArrayBuffer.empty[BigInt]
    private val labels = scala.collection.mutable.Map.empty[String, Int]
    private val patches = ArrayBuffer.empty[() => Unit]
    def pc: Int = base + 4 * code.size
    def address(name: String): Int = labels(name)
    def emit(words: BigInt*): Unit = code ++= words
    def label(name: String): Unit = { require(!labels.contains(name)); labels(name) = pc }
    def org(address: Int): Unit = {
      require(address >= pc && (address - pc) % 4 == 0)
      code ++= Seq.fill((address - pc) / 4)(Nop)
    }
    def li(rd: Int, value: BigInt): Unit = {
      val n = value & Mask
      val low = (n & 4095).toInt
      val signedLow = if (low >= 2048) low - 4096 else low
      emit(lui(rd, ((n + 2048) >> 12) & 0xfffff), addi(rd, rd, signedLow))
    }
    def la(rd: Int, name: String): Unit = {
      val index = code.size
      emit(Nop, Nop)
      patches += (() => {
        val n = BigInt(address(name))
        code(index) = lui(rd, ((n + 2048) >> 12) & 0xfffff)
        code(index + 1) = addi(rd, rd, (n & 4095).toInt)
      })
    }
    def jump(name: String, rd: Int = 0): Unit = {
      val index = code.size
      val from = pc
      emit(Nop)
      patches += (() => code(index) = jal(rd, address(name) - from))
    }
    def write(address: Int, value: BigInt, rs: Int = 1): Unit = {
      li(rs, value); emit(csr(0, address, rs, 1))
    }
    def vector(name: String, address: Int = 0x305, vectored: Boolean = false): Unit = {
      la(1, name)
      if (vectored) emit(addi(1, 1, 1))
      emit(csr(0, address, 1, 1))
    }
    def enter(name: String, mode: Int, status: BigInt = 0): Unit = {
      la(1, name); emit(csr(0, 0x341, 1, 1))
      write(0x300, status | (BigInt(mode) << 11)); emit(Mret)
    }
    def done(): Unit = {
      emit(addi(30, 0, DoneValue), store(30, DoneAddress)); label("halt")
      emit(jal(0, 0))
    }
    def machineLogger(skip: Int = 8, resume: Option[String] = None): Unit = {
      label("handler")
      emit(csr(20, 0x341, 0), csr(21, 0x342, 0), csr(22, 0x343, 0),
        store(20, 0, base = 28), store(21, 4, base = 28),
        store(22, 8, base = 28), addi(28, 28, 12))
      resume match {
        case Some(name) => la(20, name)
        case None => emit(addi(20, 20, skip))
      }
      emit(csr(0, 0x341, 20, 1), Mret)
    }
    def config(timeout: Int = 24, depth: Int = 2048): CpuConfig = {
      patches.foreach(_())
      val capacity = depth * 4
      val window = base / capacity * capacity
      val offset = (base - window) / 4
      require(offset + code.size <= depth)
      val content = Seq.fill(offset)(Nop) ++ code ++
        Seq.fill(depth - offset - code.size)(Nop)
      val directory = Files.createDirectories(Paths.get("target/chapter8-images"))
      val file = Files.createTempFile(directory, "acceptance-", ".hex")
      Files.write(file, content.map(w => f"$w%08x").mkString("", "\n", "\n")
        .getBytes(StandardCharsets.UTF_8))
      CpuConfig(imemDepth = depth, dmemDepth = 2048, resetVector = base,
        instructionInitFile = Some(file.toString), privilegedSupported = true,
        privileged = PrivilegedConfig(wfiTimeoutCycles = timeout))
    }
  }
  final case class TrapEvent(pc: BigInt, cause: BigInt, value: BigInt,
    from: Int = 3, to: Int = 3, interrupt: Boolean = false)
  final case class Write(address: BigInt, data: BigInt, mask: BigInt = 15)
  def log(pc: Int, cause: BigInt, value: BigInt, index: Int = 0): Seq[Write] =
    Seq(Write(TrapLog + 12 * index, pc), Write(TrapLog + 12 * index + 4, cause),
      Write(TrapLog + 12 * index + 8, value))
  val doneWrite: Write = Write(DoneAddress, DoneValue)
}

class Chapter8AcceptanceSpec extends AnyFlatSpec with ChiselScalatestTester {
  import Chapter8Programs._
  behavior of "Chapter 8 real privileged pipeline acceptance"

  private def resetCpu(d: Chapter8Harness): Unit = {
    d.interrupts.MExtInt.poke(false.B)
    d.interrupts.SExtInt.poke(false.B)
    d.interrupts.MTimerInt.poke(false.B)
    d.interrupts.MSwInt.poke(false.B)
    d.interrupts.TIME_REGW.poke(0.U)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
  }
  private final class Run(d: Chapter8Harness) {
    val writes = ArrayBuffer.empty[Write]
    val traps = ArrayBuffer.empty[TrapEvent]
    var cycles = 0
    def tick(before: () => Unit = () => ()): Unit = {
      before()
      d.interrupts.TIME_REGW.poke(cycles.U)
      if (d.io.MemWrite.peek().litToBoolean) {
        writes += Write(d.io.IEUAdr.peek().litValue, d.io.WriteData.peek().litValue,
          d.io.ByteMask.peek().litValue)
      }
      val event = if (d.Trap.peek().litToBoolean) {
        Some((d.PCM.peek().litValue, d.Cause.peek().litValue,
          d.Mode.peek().litValue.toInt, d.NextMode.peek().litValue.toInt,
          d.Interrupt.peek().litToBoolean))
      } else None
      d.clock.step(); cycles += 1
      event.foreach { case (pc, cause, from, to, interrupt) =>
        val recordedPC = if (to == 3) d.MEPC.peek().litValue else d.SEPC.peek().litValue
        val recordedCause = if (to == 3) d.MCause.peek().litValue else d.SCause.peek().litValue
        val value = if (to == 3) d.MTval.peek().litValue else d.STval.peek().litValue
        assert(recordedPC == pc, s"trap EPC mismatch at cycle $cycles")
        assert(recordedCause == (cause | (if (interrupt) BigInt(1) << 31 else BigInt(0))))
        assert(d.Mode.peek().litValue == to, s"trap mode mismatch at cycle $cycles")
        traps += TrapEvent(pc, cause, value, from, to, interrupt)
      }
    }
    def until(predicate: => Boolean, limit: Int = 1000,
      before: () => Unit = () => ()): Unit = {
      while (!predicate && cycles < limit) tick(before)
      assert(predicate, s"CPU did not reach expected boundary by cycle $limit; " +
        s"pc=${d.PCM.peek().litValue.toString(16)}, traps=$traps, writes=$writes")
    }
    def finish(limit: Int = 1000, before: () => Unit = () => ()): Unit = {
      until(writes.contains(doneWrite), limit, before)
      for (_ <- 0 until 8) tick(before)
    }
    def expect(expectedWrites: Seq[Write], expectedTraps: Seq[TrapEvent]): Unit = {
      assert(writes.toSeq == expectedWrites, s"memory signature mismatch: $writes")
      assert(traps.toSeq == expectedTraps, s"unexpected/missing/repeated trap: $traps")
    }
  }
  private def faultSetup(p: Program): Unit = {
    p.vector("handler"); p.li(28, TrapLog); p.emit(addi(2, 0, 0x33))
  }

  it should "execute all six CSR RMW forms with late rd and forwarded rs dependencies" in {
    val p = new Program
    p.emit(addi(1, 0, 0x12), csr(2, 0x340, 1, 1), addi(3, 2, 1),
      csr(4, 0x340, 1, 2), addi(1, 0, 3), csr(5, 0x340, 1, 3),
      csr(6, 0x340, 7, 5), csr(7, 0x340, 8, 6), csr(8, 0x340, 2, 7),
      csr(9, 0x340, 0), csr(0, 0x340, 1, 1), csr(10, 0x340, 0),
      csr(11, 0x340, 0, 6), csr(0, 0x340, 0, 7), addi(1, 0, 0x55),
      csr(12, 0x340, 1, 1), csr(13, 0x340, 0))
    val values = Seq(0, 1, 0x12, 0x12, 0x10, 7, 15, 13, 3, 3, 3, 0x55)
    for (reg <- 2 to 13) p.emit(store(reg, 128 + 4 * (reg - 2)))
    p.done()
    test(new Chapter8Harness(p.config())) { d =>
      resetCpu(d); val r = new Run(d); r.finish()
      r.expect(values.zipWithIndex.map { case (n, i) => Write(128 + 4 * i, n) } :+
        doneWrite, Seq.empty)
      d.Scratch.expect(0x55.U); d.Registers(0).expect(0.U)
      values.zipWithIndex.foreach { case (n, i) => d.Registers(i + 2).expect(n.U) }
    }
  }

  it should "preserve old W and cancel faulting rd, younger store and redirect" in {
    val p = new Program
    faultSetup(p); p.emit(addi(5, 0, 7), addi(6, 0, 9), addi(5, 0, 0x44))
    p.label("fault"); val illegal = csr(6, 0x777, 0)
    p.emit(illegal); p.jump("poison"); p.emit(store(2, 160))
    p.label("resume"); p.emit(store(5, 128), store(6, 132)); p.done()
    p.label("poison"); p.emit(store(2, 164)); p.emit(jal(0, 0))
    p.org(768); p.machineLogger(resume = Some("resume"))
    test(new Chapter8Harness(p.config())) { d =>
      resetCpu(d); val r = new Run(d); r.finish()
      r.expect(log(p.address("fault"), 2, illegal) ++
        Seq(Write(128, 0x44), Write(132, 9), doneWrite),
        Seq(TrapEvent(p.address("fault"), 2, illegal)))
      d.Registers(5).expect(0x44.U); d.Registers(6).expect(9.U)
    }
  }

  it should "record illegal CSR, breakpoint, integer and fence encodings" in {
    val p = new Program
    faultSetup(p)
    val instructions = Seq(csr(3, 0x777, 0), csr(3, 0xf11, 2, 1),
      Ebreak, BigInt("ffffffff", 16), BigInt("0000200f", 16))
    val faultPCs = instructions.zipWithIndex.map { case (word, i) =>
      val pc = p.pc
      p.emit(word, store(2, 160 + 4 * i)); pc
    }
    p.emit(csr(3, 0x340, 0), store(3, 128)); p.done()
    p.org(768); p.machineLogger()
    val events = instructions.zip(faultPCs).map { case (word, pc) =>
      TrapEvent(pc, if (word == Ebreak) 3 else 2, if (word == Ebreak) pc else word)
    }
    test(new Chapter8Harness(p.config())) { d =>
      resetCpu(d); val r = new Run(d); r.finish(1500)
      r.expect(events.zipWithIndex.flatMap { case (e, i) =>
        log(e.pc.toInt, e.cause, e.value, i)
      } ++ Seq(Write(128, 0), doneWrite), events)
      d.Scratch.expect(0.U)
    }
  }

  it should "trap U/S CSR privilege reads/writes and return through real MRET" in {
    for (mode <- Seq(0, 1)) {
      val p = new Program
      faultSetup(p); p.enter("user", mode)
      p.label("user")
      val words = Seq(csr(3, 0x300, 0), csr(3, 0x300, 2, 1), Ecall)
      val pcs = words.map { word => val pc = p.pc; p.emit(word, store(2, 160)); pc }
      p.emit(addi(4, 0, 0x21), store(4, 128)); p.done()
      p.org(768); p.machineLogger()
      val events = words.zip(pcs).map { case (word, pc) =>
        TrapEvent(pc, if (word == Ecall) 8 + mode else 2,
          if (word == Ecall) 0 else word, mode, 3)
      }
      test(new Chapter8Harness(p.config())) { d =>
        resetCpu(d); val r = new Run(d); r.finish(1500)
        r.expect(events.zipWithIndex.flatMap { case (e, i) =>
          log(e.pc.toInt, e.cause, e.value, i)
        } ++ Seq(Write(128, 0x21), doneWrite), events)
        d.Mode.expect(mode.U)
      }
    }
  }

  it should "execute M/U/S ECALL with delegation and nested MRET/SRET PC restoration" in {
    val p = new Program
    faultSetup(p); p.vector("supervisor", 0x105)
    p.write(0x302, (1 << 8) | (1 << 11))
    p.label("mcall"); p.emit(Ecall, Nop)
    p.enter("user", 0)
    p.label("user"); p.label("ucall"); p.emit(Ecall)
    p.emit(addi(4, 0, 0x27), store(4, 128)); p.done()
    p.org(768); p.machineLogger()
    p.org(896); p.label("supervisor")
    p.emit(csr(10, 0x141, 0), csr(11, 0x142, 0), csr(12, 0x143, 0),
      store(10, 140), store(11, 144), store(12, 148))
    p.label("scall"); p.emit(Ecall, Nop)
    p.emit(addi(10, 10, 4), csr(0, 0x141, 10, 1), Sret)
    test(new Chapter8Harness(p.config())) { d =>
      resetCpu(d); val r = new Run(d); r.finish(1800)
      r.expect(log(p.address("mcall"), 11, 0) ++
        Seq(Write(140, p.address("ucall")), Write(144, 8), Write(148, 0)) ++
        log(p.address("scall"), 9, 0, 1) ++ Seq(Write(128, 0x27), doneWrite),
        Seq(TrapEvent(p.address("mcall"), 11, 0),
          TrapEvent(p.address("ucall"), 8, 0, 0, 1),
          TrapEvent(p.address("scall"), 9, 0, 1, 3)))
      d.Mode.expect(0.U); d.SEPC.expect((p.address("ucall") + 4).U)
    }
  }

  it should "assign taken branch/JAL/JALR misalignment to source EPC and target tval" in {
    val p = new Program
    faultSetup(p); p.emit(addi(7, 0, 0x66))
    val branchPC = p.pc; p.emit(branch(0, 0, 2), store(2, 160))
    val jalPC = p.pc; p.emit(jal(7, 2), store(2, 164))
    p.li(1, 258); val jalrPC = p.pc; p.emit(jalr(7, 1), store(2, 168))
    p.emit(store(7, 128)); p.done(); p.org(768); p.machineLogger()
    val events = Seq(TrapEvent(branchPC, 0, branchPC + 2),
      TrapEvent(jalPC, 0, jalPC + 2), TrapEvent(jalrPC, 0, 258))
    test(new Chapter8Harness(p.config())) { d =>
      resetCpu(d); val r = new Run(d); r.finish(1500)
      r.expect(events.zipWithIndex.flatMap { case (e, i) =>
        log(e.pc.toInt, e.cause, e.value, i)
      } ++ Seq(Write(128, 0x66), doneWrite), events)
      d.Registers(7).expect(0x66.U)
    }
  }

  it should "discard wrong-path faults and ignore the target of a not-taken branch" in {
    val p = new Program
    p.emit(addi(1, 0, 1), branch(0, 1, 2))
    p.jump("safe"); p.emit(Ebreak, BigInt("ffffffff", 16), store(1, 160))
    p.label("safe"); p.emit(addi(2, 0, 0x38), store(2, 128)); p.done()
    test(new Chapter8Harness(p.config())) { d =>
      resetCpu(d); val r = new Run(d); r.finish()
      r.expect(Seq(Write(128, 0x38), doneWrite), Seq.empty)
      d.MCause.expect(0.U)
    }
  }

  it should "trap load/store alignment and bounds without corrupting a wrapped RAM alias" in {
    val p = new Program
    faultSetup(p); p.li(1, 8192); p.emit(store(2, 0), addi(5, 0, 0x77))
    val cases = Seq((load(5, 0, 2), 4, 2), (store(2, 2), 6, 2),
      (load(5, 1), 5, 8192), (store(2, 0, base = 1), 7, 8192))
    val events = cases.map { case (word, cause, value) =>
      val pc = p.pc; p.emit(word, store(2, 160)); TrapEvent(pc, cause, value)
    }
    p.emit(load(6, 0), store(6, 128), store(5, 132)); p.done()
    p.org(768); p.machineLogger()
    test(new Chapter8Harness(p.config())) { d =>
      resetCpu(d); val r = new Run(d); r.finish(1800)
      r.expect(Seq(Write(0, 0x33)) ++ events.zipWithIndex.flatMap { case (e, i) =>
        log(e.pc.toInt, e.cause, e.value, i)
      } ++ Seq(Write(128, 0x33), Write(132, 0x77), doneWrite), events)
      d.Registers(5).expect(0x77.U)
    }
  }

  it should "fault out-of-window fetch at nonzero reset without executing alias" in {
    val p = new Program(0x4100)
    p.vector("handler"); p.li(28, TrapLog); p.li(1, 0x3ffc)
    p.emit(jalr(0, 1)); p.label("resume")
    p.emit(addi(2, 0, 0x39), store(2, 128)); p.done()
    p.org(0x4300); p.machineLogger(resume = Some("resume"))
    test(new Chapter8Harness(p.config(depth = 256))) { d =>
      resetCpu(d); val r = new Run(d); r.finish()
      r.expect(log(0x3ffc, 1, 0x3ffc) ++ Seq(Write(128, 0x39), doneWrite),
        Seq(TrapEvent(0x3ffc, 1, 0x3ffc)))
    }
  }

  it should "deliver direct and vectored M interrupts, acknowledge pending and MRET once" in {
    for (vectored <- Seq(false, true)) {
      val p = new Program
      p.vector("vector", vectored = vectored); p.write(0x304, 1 << 11)
      p.write(0x300, (3 << 11) | 8)
      p.label("boundary"); p.emit(addi(5, 0, 0x48), store(5, 128)); p.done()
      p.org(768); p.label("vector")
      if (vectored) {
        // Direct base is poison; only slot 11 may enter the actual handler.
        p.emit(store(0, 160), jal(0, 0)); p.org(768 + 4 * 11)
      }
      p.label("irqhandler")
      p.emit(csr(20, 0x341, 0), csr(21, 0x342, 0), csr(22, 0x343, 0),
        store(20, 140), store(21, 144), store(22, 148), Mret)
      test(new Chapter8Harness(p.config())) { d =>
        resetCpu(d); val r = new Run(d)
        var raised = false; var cleared = false
        val stimulus = () => {
          if (!raised && d.ValidM.peek().litToBoolean &&
            d.PCM.peek().litValue == p.address("boundary")) {
            d.interrupts.MExtInt.poke(true.B); raised = true
            d.RedirectPC.expect(p.address("irqhandler").U)
          }
          if (raised && !cleared && d.ValidM.peek().litToBoolean &&
            d.PCM.peek().litValue == p.address("irqhandler")) {
            d.interrupts.MExtInt.poke(false.B); cleared = true
          }
        }
        r.finish(1200, stimulus)
        assert(raised && cleared)
        r.expect(Seq(Write(140, p.address("boundary")), Write(144, BigInt("8000000b", 16)),
          Write(148, 0), Write(128, 0x48), doneWrite),
          Seq(TrapEvent(p.address("boundary"), 11, 0, interrupt = true)))
        d.Pending.expect(0.U); d.Mode.expect(3.U)
      }
    }
  }

  it should "delegate external S interrupts and restore S PC and SIE with SRET" in {
    val p = new Program
    p.vector("handler", 0x105); p.write(0x303, 1 << 9)
    p.write(0x304, 1 << 9); p.enter("supervisor", 1, status = 2)
    p.label("supervisor"); p.label("boundary")
    p.emit(addi(5, 0, 0x49), store(5, 128)); p.done()
    p.org(768); p.label("handler")
    p.emit(csr(20, 0x141, 0), csr(21, 0x142, 0), csr(22, 0x143, 0),
      store(20, 140), store(21, 144), store(22, 148), Sret)
    test(new Chapter8Harness(p.config())) { d =>
      resetCpu(d); val r = new Run(d)
      var raised = false; var cleared = false
      val stimulus = () => {
        if (!raised && d.ValidM.peek().litToBoolean &&
          d.PCM.peek().litValue == p.address("boundary")) {
          d.interrupts.SExtInt.poke(true.B); raised = true
        }
        if (raised && !cleared && d.ValidM.peek().litToBoolean &&
          d.PCM.peek().litValue == p.address("handler")) {
          d.interrupts.SExtInt.poke(false.B); cleared = true
        }
      }
      r.finish(1200, stimulus); assert(raised && cleared)
      r.expect(Seq(Write(140, p.address("boundary")), Write(144, BigInt("80000009", 16)),
        Write(148, 0), Write(128, 0x49), doneWrite),
        Seq(TrapEvent(p.address("boundary"), 9, 0, 1, 1, interrupt = true)))
      d.Pending.expect(0.U); d.Mode.expect(1.U)
      assert(d.Status.peek().litValue.testBit(1))
    }
  }

  it should "set software SSIP, reenter after MRET/SRET and clear pending in the handler" in {
    for (delegated <- Seq(false, true)) {
      val p = new Program
      val mode = if (delegated) 1 else 3
      val epc = if (delegated) 0x141 else 0x341
      val cause = if (delegated) 0x142 else 0x342
      val tval = if (delegated) 0x143 else 0x343
      val pending = if (delegated) 0x144 else 0x344
      val ret = if (delegated) Sret else Mret
      p.vector("handler", if (delegated) 0x105 else 0x305)
      if (delegated) p.write(0x303, 2)
      p.write(0x304, 2)
      p.emit(addi(6, 0, 0), addi(7, 0, 2)); p.li(28, TrapLog)
      // Both pending assertion and acknowledgement are executed CSR instructions.
      p.write(0x344, 2)
      if (delegated) p.enter("first", 1, status = 2)
      else p.write(0x300, (3 << 11) | 8)
      p.label("first"); p.emit(store(0, 160))
      p.label("second"); p.emit(addi(5, 0, 0x61), store(5, 128)); p.done()
      p.org(768); p.label("handler")
      p.emit(addi(6, 6, 1), csr(20, epc, 0), csr(21, cause, 0),
        csr(22, tval, 0), csr(23, pending, 0), store(20, 0, base = 28),
        store(21, 4, base = 28), store(22, 8, base = 28),
        store(23, 12, base = 28), addi(28, 28, 16), branch(6, 7, 20))
      // First return deliberately preserves SSIP and advances past the poison store.
      p.la(20, "second"); p.emit(csr(0, epc, 20, 1), ret)
      // The branch target is five instructions after the branch: clear on entry 2.
      p.emit(csr(0, pending, 0, 1), ret)
      test(new Chapter8Harness(p.config())) { d =>
        resetCpu(d); val r = new Run(d)
        val returnPending = ArrayBuffer.empty[BigInt]
        val observe: () => Unit = () => {
          if (d.ValidM.peek().litToBoolean && d.PCM.peek().litValue >= 768 &&
            d.InstrM.peek().litValue == ret) {
            returnPending += d.Pending.peek().litValue
          }
        }
        r.finish(1500, observe)
        val interruptCause = BigInt("80000001", 16)
        val records = Seq("first", "second").zipWithIndex.flatMap { case (name, i) =>
          Seq(Write(TrapLog + 16 * i, p.address(name)),
            Write(TrapLog + 16 * i + 4, interruptCause),
            Write(TrapLog + 16 * i + 8, 0), Write(TrapLog + 16 * i + 12, 2))
        }
        r.expect(records ++ Seq(Write(128, 0x61), doneWrite),
          Seq(TrapEvent(p.address("first"), 1, 0, mode, mode, interrupt = true),
            TrapEvent(p.address("second"), 1, 0, mode, mode, interrupt = true)))
        assert(returnPending.toSeq == Seq(BigInt(2), BigInt(0)),
          s"SSIP did not persist across the first return or clear before the second")
        d.Registers(6).expect(2.U); d.Pending.expect(0.U); d.Mode.expect(mode.U)
      }
    }
  }

  it should "retire FENCE/FENCE.I once and refetch younger operations in program order" in {
    val p = new Program
    val fence = BigInt("0ff0000f", 16)
    val fenceI = BigInt("0000100f", 16)
    // Reserved rd/rs1 and FENCE.I immediate fields are ignored, never written back.
    val reservedFence = fence | (BigInt(31) << 15) | (BigInt(31) << 7)
    val reservedFenceI = fenceI | (BigInt(0x5a5) << 20) |
      (BigInt(31) << 15) | (BigInt(31) << 7)
    p.emit(addi(31, 0, 0x7c), addi(5, 0, 0x31), store(5, 0), addi(6, 0, 0x32))
    val first = p.pc; p.emit(fence, addi(7, 6, 1), store(7, 4), load(8, 0, 4))
    val second = p.pc; p.emit(reservedFence, addi(9, 8, 1), store(9, 8))
    val third = p.pc; p.emit(fenceI, load(10, 0, 8), addi(11, 10, 1))
    val fourth = p.pc; p.emit(reservedFenceI, store(11, 12))
    val regs = Seq(6, 7, 8, 9, 10, 11, 31)
    val values = Seq(0x32, 0x33, 0x33, 0x34, 0x34, 0x35, 0x7c)
    regs.zipWithIndex.foreach { case (reg, i) => p.emit(store(reg, 128 + 4 * i)) }
    p.done()
    test(new Chapter8Harness(p.config())) { d =>
      resetCpu(d); val r = new Run(d)
      val fences = Seq(first, second, third, fourth).map(BigInt(_))
      val redirected = ArrayBuffer.empty[BigInt]
      val retiredPCs = ArrayBuffer.empty[BigInt]
      val observe: () => Unit = () => {
        val pc = d.PCM.peek().litValue
        if (d.Retired.peek().litToBoolean && pc != p.address("halt")) retiredPCs += pc
        if (d.ValidM.peek().litToBoolean && fences.contains(pc)) {
          d.Redirect.expect(true.B); d.RedirectPC.expect((pc + 4).U)
          d.Retired.expect(true.B); d.Trap.expect(false.B); d.io.MemRW.expect(0.U)
          redirected += pc
        }
      }
      r.finish(1000, observe)
      r.expect(Seq(Write(0, 0x31), Write(4, 0x33), Write(8, 0x34), Write(12, 0x35)) ++
        values.zipWithIndex.map { case (n, i) => Write(128 + 4 * i, n) } :+
        doneWrite, Seq.empty)
      assert(redirected.toSeq == fences, s"missing or repeated fence redirect: $redirected")
      assert(retiredPCs.toSeq == (0 until p.address("halt") by 4).map(BigInt(_)),
        s"instructions were lost, duplicated or retired out of order: $retiredPCs")
      regs.zip(values).foreach { case (reg, n) => d.Registers(reg).expect(n.U) }
    }
  }

  it should "hold WFI in M, consume old W once, retain young forwarding and wake locally" in {
    val p = new Program
    p.write(0x304, 1 << 7)
    p.emit(addi(5, 0, 41)); p.label("wait")
    p.emit(Wfi, addi(6, 5, 1), store(6, 128)); p.label("timeoutwait")
    p.emit(Wfi, addi(7, 6, 1), store(7, 132)); p.done()
    test(new Chapter8Harness(p.config(timeout = 12))) { d =>
      resetCpu(d); val r = new Run(d)
      r.until(d.WFIStall.peek().litToBoolean)
      val pcF = d.PCF.peek().litValue; val pcD = d.PCD.peek().litValue
      val retired = d.Instret.peek().litValue
      var oldWrites = 0
      for (_ <- 0 until 5) {
        d.PCM.expect(p.address("wait").U); d.InstrM.expect(Wfi.U)
        d.PCF.expect(pcF.U); d.PCD.expect(pcD.U); d.Instret.expect(retired.U)
        if (d.RegWriteW.peek().litToBoolean && d.RdW.peek().litValue == 5) oldWrites += 1
        r.tick()
      }
      assert(oldWrites == 1, s"old W was committed $oldWrites times")
      d.Registers(5).expect(41.U)
      d.interrupts.MTimerInt.poke(true.B)
      d.WFIStall.expect(false.B); d.Trap.expect(false.B); d.Retired.expect(true.B)
      r.tick(); d.Instret.expect((retired + 1).U)
      d.interrupts.MTimerInt.poke(false.B)
      r.until(d.WFIStall.peek().litToBoolean &&
        d.PCM.peek().litValue == p.address("timeoutwait"))
      val second = d.Instret.peek().litValue
      r.until(d.WFITimeout.peek().litToBoolean)
      d.Trap.expect(false.B); d.Retired.expect(true.B)
      d.Instret.expect(second.U); r.tick(); d.Instret.expect((second + 1).U)
      r.finish()
      r.expect(Seq(Write(128, 42), Write(132, 43), doneWrite), Seq.empty)
      d.Registers(6).expect(42.U); d.Registers(7).expect(43.U)
    }
  }

  it should "retire a pending-woken WFI before interrupting its successor and returning" in {
    val p = new Program
    p.vector("handler"); p.write(0x304, 1 << 7); p.write(0x300, (3 << 11) | 8)
    p.label("wait"); p.emit(Wfi); p.label("successor")
    p.emit(addi(5, 0, 0x51), store(5, 128)); p.done()
    p.org(768); p.label("handler")
    p.emit(csr(20, 0x341, 0), csr(21, 0x342, 0), store(20, 140),
      store(21, 144), Mret)
    test(new Chapter8Harness(p.config(timeout = 32))) { d =>
      resetCpu(d); val r = new Run(d)
      r.until(d.WFIStall.peek().litToBoolean)
      val retired = d.Instret.peek().litValue
      d.interrupts.MTimerInt.poke(true.B)
      d.Trap.expect(false.B); d.Retired.expect(true.B)
      r.tick(); d.Instret.expect((retired + 1).U)
      var cleared = false
      val stimulus = () => {
        if (!cleared && d.ValidM.peek().litToBoolean &&
          d.PCM.peek().litValue == p.address("handler")) {
          d.interrupts.MTimerInt.poke(false.B); cleared = true
        }
      }
      r.finish(1200, stimulus); assert(cleared)
      r.expect(Seq(Write(140, p.address("successor")),
        Write(144, BigInt("80000007", 16)), Write(128, 0x51), doneWrite),
        Seq(TrapEvent(p.address("successor"), 7, 0, interrupt = true)))
    }
  }

  it should "bound legal S waits and report restricted S TW/U WFI timeout precisely" in {
    val legal = new Program
    legal.enter("supervisor", 1)
    legal.label("supervisor"); legal.emit(Wfi, addi(5, 0, 0x53), store(5, 128))
    legal.done()
    test(new Chapter8Harness(legal.config(timeout = 7))) { d =>
      resetCpu(d); val r = new Run(d)
      r.until(d.WFIStall.peek().litToBoolean)
      val started = r.cycles; val retired = d.Instret.peek().litValue
      r.until(d.WFITimeout.peek().litToBoolean, limit = started + 12)
      d.Trap.expect(false.B); d.Instret.expect(retired.U)
      r.tick(); d.Instret.expect((retired + 1).U)
      r.finish()
      r.expect(Seq(Write(128, 0x53), doneWrite), Seq.empty)
      d.Mode.expect(1.U)
    }
    for (mode <- Seq(1, 0)) {
      val p = new Program
      faultSetup(p); p.enter("lower", mode, status = BigInt(1) << 21)
      p.label("lower"); p.label("wait"); p.emit(Wfi, store(2, 160))
      p.emit(addi(5, 0, 0x52), store(5, 128)); p.done()
      p.org(768); p.machineLogger()
      test(new Chapter8Harness(p.config(timeout = 7))) { d =>
        resetCpu(d); val r = new Run(d)
        r.until(d.WFIStall.peek().litToBoolean)
        val started = r.cycles; val retired = d.Instret.peek().litValue
        r.until(d.Trap.peek().litToBoolean, limit = started + 12)
        d.WFITimeout.expect(true.B); d.Instret.expect(retired.U)
        r.finish(1200)
        r.expect(log(p.address("wait"), 2, Wfi) ++ Seq(Write(128, 0x52), doneWrite),
          Seq(TrapEvent(p.address("wait"), 2, Wfi, mode, 3)))
        d.Mode.expect(mode.U)
      }
    }
  }

  it should "execute endian word/byte/half and MPRV while preserving instruction fetch" in {
    val p = new Program
    p.li(1, BigInt("11223344", 16)); p.emit(store(1, 0))
    p.write(0x310, 0x20)
    p.emit(load(2, 0), load(3, 0, 0, 4), load(4, 0, 0, 5))
    p.li(1, BigInt("a1b2c3d4", 16)); p.emit(store(1, 4))
    p.emit(addi(1, 0, 0x5e), store(1, 1, 0))
    p.li(1, 0x6677); p.emit(store(1, 2, 1), load(5, 0))
    p.write(0x310, 0)
    p.emit(load(6, 0), load(7, 0, 4), load(8, 0, 1, 4), load(9, 0, 2, 5))
    // MBE=0, UBE=1, MPRV=1 and MPP=U selects big endian only for data.
    p.write(0x300, (BigInt(1) << 17) | 0x40)
    p.emit(load(10, 0), load(11, 0, 2, 5)); p.li(1, 0x1234)
    p.emit(store(1, 8, 1))
    p.write(0x300, 0)
    p.emit(load(12, 0, 8, 5))
    for (reg <- 2 to 12) p.emit(store(reg, 128 + 4 * (reg - 2)))
    p.done()
    val values = Seq[BigInt](BigInt("44332211", 16), 0x44, 0x4433,
      BigInt("445e6677", 16), BigInt("77665e44", 16), BigInt("d4c3b2a1", 16),
      0x5e, 0x7766, BigInt("445e6677", 16), 0x6677, 0x3412)
    test(new Chapter8Harness(p.config())) { d =>
      resetCpu(d); val r = new Run(d); r.finish(1800)
      val setupWrites = Seq(Write(0, BigInt("11223344", 16)),
        Write(4, BigInt("a1b2c3d4", 16)), Write(1, 0x5e, 2), Write(2, 0x6677, 12),
        Write(8, 0x1234, 3))
      r.expect(setupWrites ++ values.zipWithIndex.map { case (n, i) =>
        Write(128 + 4 * i, n)
      } :+ doneWrite, Seq.empty)
      values.zipWithIndex.foreach { case (n, i) => d.Registers(i + 2).expect(n.U) }
      d.StatusHigh.expect(0.U); d.Mode.expect(3.U)
    }
  }
}
