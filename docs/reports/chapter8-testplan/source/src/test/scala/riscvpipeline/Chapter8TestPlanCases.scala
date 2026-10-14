package riscvpipeline

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import riscvpipeline.config.CpuConfig
import riscvpipeline.privileged.PrivilegedConfig
import TestEncoding.{CSR, CSRFunction, Privilege}

/** Independent architectural fixtures shared by chiseltest and top-port-only RTL simulation.
  * No expected value is obtained from the DUT. The existing hand encoder is reused.
  */
object Chapter8TestPlanCases {
  import Chapter8Programs._
  private val base = Paths.get("target/chapter8-testplan")
  private val pointer = base.resolve("current-run.txt")
  val Root: Path = if (Files.exists(pointer)) Paths.get(Files.readString(pointer).trim) else base
  val Raise = 600
  val Ack = 604
  final case class Variant(name: String, s: Boolean = true, be: Boolean = true,
    stc: Boolean = false, hpm: Int = 0, reset: Int = 0) {
    def privileged: PrivilegedConfig = PrivilegedConfig(supervisorSupported = s,
      bigEndianSupported = be, sstcSupported = stc, hpmCounters = hpm,
      wfiTimeoutCycles = 24)
  }
  val variants = Seq(Variant("default"), Variant("minimal", s = false, be = false),
    Variant("sstc-hpm", stc = true, hpm = 3), Variant("reset100", reset = 256))
  // Pin bits: M external, S external, M timer, M software. Delays occur only during WFI.
  final case class Stimulus(pins: Int = 0, timeInitial: BigInt = 0,
    timeRaised: BigInt = 0, delayed: Boolean = false)
  final case class Case(id: String, title: String, category: String, book: String,
    cvw: String, variant: Variant, config: CpuConfig, expected: Seq[Write],
    stimulus: Stimulus = Stimulus(), limit: Int = 10000) {
    val key: String = s"${variant.name}-$id"
  }
  private def fixture(v: Variant, id: String, title: String, category: String,
    book: String, cvw: String, p: Program, expected: Seq[Write],
    stimulus: Stimulus = Stimulus()): Case = {
    val directory = Files.createDirectories(Root.resolve("cases"))
    val path = directory.resolve(s"${v.name}-$id.hex")
    val generated = p.config()
    Files.copy(Paths.get(generated.instructionInitFile.get), path,
      StandardCopyOption.REPLACE_EXISTING)
    Case(id, title, category, book, cvw, v,
      generated.copy(instructionInitFile = Some(path.toString), privileged = v.privileged),
      expected :+ doneWrite, stimulus)
  }
  private def setup(p: Program): Unit = { p.vector("handler"); p.li(28, TrapLog) }
  private def logger(p: Program, s: Boolean = false, skip: Int = 8,
    resume: Option[String] = None, clear: Boolean = false, pins: Int = 0): Unit = {
    val epc = if (s) CSR.SEPC else CSR.MEPC
    p.label("handler")
    p.emit(csr(20, epc, 0), csr(21, if (s) CSR.SCAUSE else CSR.MCAUSE, 0),
      csr(22, if (s) CSR.STVAL else CSR.MTVAL, 0),
      csr(23, if (s) CSR.SSTATUS else CSR.MSTATUS, 0),
      csr(24, if (s) CSR.SIP else CSR.MIP, 0))
    for (r <- 20 to 24) p.emit(store(r, (r - 20) * 4, base = 28))
    p.emit(addi(28, 28, 20))
    if (clear) {
      p.write(if (s) CSR.SIP else CSR.MIP, 0)
      if (s) p.write(CSR.SIE, 0) // STIP is read-only through SIP.
    }
    if (pins != 0) p.emit(store(0, Ack))
    resume match {
      case Some(label) => p.la(20, label)
      case None => if (skip != 0) p.emit(addi(20, 20, skip))
    }
    p.emit(csr(0, epc, 20, CSRFunction.RW), if (s) Sret else Mret)
  }
  private def trap(pc: Int, cause: BigInt, tval: BigInt, status: BigInt,
    pending: BigInt = 0, index: Int = 0): Seq[Write] =
    Seq[BigInt](pc, cause, tval, status, pending).zipWithIndex.map {
      case (value, i) => Write(TrapLog + index * 20 + i * 4, value)
    }
  private def read(p: Program, addr: Int, signature: Int): Unit =
    p.emit(csr(5, addr, 0), store(5, signature))

  def cases(v: Variant): Seq[Case] = {
    val out = scala.collection.mutable.ArrayBuffer.empty[Case]
    def add(id: String, title: String, category: String, book: String, cvw: String,
      p: Program, writes: Seq[Write], stimulus: Stimulus = Stimulus()): Unit =
      out += fixture(v, id, title, category, book, cvw, p, writes, stimulus)
    def program = new Program(v.reset)
    // All eleven non-page exceptions, including fetch bounds and each ECALL privilege.
    for (cause <- Seq(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11) if cause != 9 || v.s) {
      val p = program; setup(p); p.emit(addi(5, 0, 0x44), addi(6, 0, 0x77))
      val mode = if (cause == 8) 0 else if (cause == 9) 1 else 3
      if (mode != 3) { p.enter("lower", mode); p.label("lower") }
      if (cause == 1 || cause == 5 || cause == 7) p.li(1, 8192)
      val pc = p.pc
      val word: BigInt = cause match {
        case 0 => jal(6, 2)
        case 1 => jalr(0, 1)
        case 2 => BigInt("ffffffff", 16)
        case 3 => Ebreak
        case 4 => load(6, 0, 2)
        case 5 => load(6, 1)
        case 6 => store(5, 2)
        case 7 => store(5, 0, base = 1)
        case _ => Ecall
      }
      p.emit(word, store(5, 160)); p.label("resume")
      p.emit(store(5, 128), store(6, 132)); p.done()
      p.org(v.reset + 1536); logger(p, resume = Some("resume"))
      val epc = if (cause == 1) 8192 else pc
      val tval: BigInt = cause match {
        case 0 => pc + 2
        case 1 | 5 | 7 => 8192
        case 2 => word
        case 3 => pc
        case 4 | 6 => 2
        case _ => 0
      }
      val status = mode << 11
      add(f"EX$cause%02d", s"非页异常 cause=$cause 与精确副作用", "exception",
        "8.8", "WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01", p,
        trap(epc, cause, tval, status) ++ Seq(Write(128, 0x44), Write(132, 0x77)))
    }
    {
      val p = program; setup(p)
      p.emit(addi(5, 0, 7), addi(6, 0, 9), addi(5, 0, 0x44))
      val pc = p.pc; val word = csr(6, 0x777, 0)
      p.emit(word); p.jump("poison"); p.emit(store(5, 160))
      p.label("resume"); p.emit(store(5, 128), store(6, 132)); p.done()
      p.label("poison"); p.emit(store(5, 164), jal(0, 0))
      p.org(v.reset + 1536); logger(p, resume = Some("resume"))
      add("PR01", "老指令完成、故障rd保留、年轻跳转与存储取消", "precise", "8.3",
        "WALLY-trap-01", p, trap(pc, 2, word, 0x1800) ++
        Seq(Write(128, 0x44), Write(132, 9)))
    }
    {
      val p = program; p.emit(addi(1, 0, 1), branch(0, 1, 2)); p.jump("safe")
      p.emit(Ebreak, BigInt("ffffffff", 16), store(1, 160))
      p.label("safe"); p.emit(store(1, 128)); p.done()
      add("PR02", "取消错误路径异常及未采取分支的未对齐目标", "precise", "8.8",
        "WALLY-trap-01", p, Seq(Write(128, 1)))
    }
    {
      val p = program
      p.emit(addi(1, 0, 0x12), csr(2, CSR.MSCRATCH, 1, 1), addi(3, 2, 1),
        csr(4, CSR.MSCRATCH, 1, 2), addi(1, 0, 3), csr(5, CSR.MSCRATCH, 1, 3),
        csr(6, CSR.MSCRATCH, 7, 5), csr(7, CSR.MSCRATCH, 8, 6),
        csr(8, CSR.MSCRATCH, 2, 7), csr(9, CSR.MSCRATCH, 0),
        csr(0, CSR.MSCRATCH, 1, 1), csr(10, CSR.MSCRATCH, 0),
        csr(11, CSR.MSCRATCH, 0, 6), csr(0, CSR.MSCRATCH, 0, 7),
        addi(1, 0, 0x55), csr(12, CSR.MSCRATCH, 1, 1), csr(13, CSR.MSCRATCH, 0))
      (2 to 13).foreach(r => p.emit(store(r, 128 + 4 * (r - 2)))); p.done()
      add("CS01", "六种CSR读改写、rd/rs1抑制与依赖", "csr", "8.6", "WALLY-trap-01", p,
        Seq(0, 1, 0x12, 0x12, 0x10, 7, 15, 13, 3, 3, 3, 0x55)
          .zipWithIndex.map { case (n, i) => Write(128 + 4 * i, n) })
    }
    {
      val p = program
      val addresses = Seq(CSR.MVENDORID, CSR.MARCHID, CSR.MIMPID, CSR.MHARTID,
        CSR.MCONFIGPTR, CSR.MISA)
      addresses.zipWithIndex.foreach { case (a, i) => read(p, a, 128 + 4 * i) }
      p.write(CSR.MISA, 0); read(p, CSR.MISA, 152); p.done()
      val misa = if (v.s) BigInt("40140100", 16) else BigInt("40100100", 16)
      add("CS02", "身份寄存器及固定RV32 I/U/S misa", "csr", "8.6", "WALLY-minfo-01/WALLY-misa-01",
        p, (Seq[BigInt](0, 0, 0, 0, 0, misa, misa)).zipWithIndex.map {
          case (n, i) => Write(128 + 4 * i, n) })
    }
    {
      val p = program
      val controls = Seq((CSR.MEDELEG, if (v.s) 0x3ff else 0),
        (CSR.MIDELEG, if (v.s) 0x222 else 0), (CSR.MIE, if (v.s) 0xaaa else 0x888),
        (CSR.MCOUNTEREN, if (v.hpm > 0) 63 else 7),
        (CSR.MCOUNTINHIBIT, if (v.hpm > 0) 61 else 5), (CSR.MENVCFG, 0))
        .filter { case (a, _) => v.s || (a != CSR.MEDELEG && a != CSR.MIDELEG) }
      controls.zipWithIndex.foreach { case ((a, _), i) =>
        p.write(a, Mask); read(p, a, 128 + 4 * i)
      }
      p.write(CSR.MIE, 0)
      p.write(CSR.MSTATUS, Mask); read(p, CSR.MSTATUS, 152)
      p.write(CSR.MSTATUSH, Mask); read(p, CSR.MSTATUSH, 156)
      p.write(CSR.MTVEC, 0x7ff); read(p, CSR.MTVEC, 160)
      p.write(CSR.MEPC, 0x7ff); read(p, CSR.MEPC, 164)
      if (v.s) { p.write(CSR.SATP, Mask); read(p, CSR.SATP, 168) }
      p.done()
      val status = if (v.s) { if (v.be) 0x7a19ea else 0x7a19aa }
        else if (v.be) 0x2218c8 else 0x221888
      add("CS03", "WARL掩码、EPC/TVEC对齐及Bare SATP", "csr", "8.6/8.7",
        "WALLY-mtvec-01/WALLY-mie-01/WALLY-satp-invalid-01", p,
        controls.zipWithIndex.map { case ((_, n), i) => Write(128 + 4 * i, n) } ++
        Seq(Write(152, status), Write(156, if (v.be) { if (v.s) 48 else 32 } else 0),
          Write(160, 0x7c1), Write(164, 0x7fc)) ++ (if (v.s) Seq(Write(168, 0)) else Nil))
    }
    // Illegal access and privileged op tests use real privilege transitions.
    val restrictions = Seq(("CS04", 3, csr(6, CSR.MVENDORID, 0, 1), BigInt(0)),
      ("CS05", 0, csr(6, CSR.MSCRATCH, 0), BigInt(0)),
      ("PV01", 0, Mret, BigInt(0)), ("PV02", 0, Sret, BigInt(0)),
      ("PV03", 3, BigInt("12000073", 16), BigInt(0))) ++
      (if (v.s) Seq(("CS06", 1, csr(6, CSR.MSCRATCH, 0), BigInt(0)),
        ("PV04", 1, Sret, BigInt(1) << 22), ("PV05", 1, Mret, BigInt(0)))
       else Seq(("CS07", 3, csr(6, CSR.SSTATUS, 0), BigInt(0)),
         ("CS08", 3, csr(6, CSR.MEDELEG, 0), BigInt(0)),
         ("PV06", 3, Sret, BigInt(0))))
    for ((id, mode, word, flags) <- restrictions) {
      val p = program; setup(p); p.emit(addi(6, 0, 0x77))
      if (mode != 3) { p.enter("lower", mode, flags); p.label("lower") }
      val pc = p.pc; p.emit(word, store(6, 160), store(6, 128)); p.done()
      p.org(v.reset + 1536); logger(p)
      val status = flags | (mode << 11)
      add(id, s"权限/只读/特权指令非法：mode=$mode word=0x${word.toString(16)}",
        if (id.startsWith("CS")) "csr" else "privileged", "8.5/8.6/8.7",
        "WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01",
        p, trap(pc, 2, word, status) ++ Seq(Write(128, 0x77)))
    }
    {
      val p = program
      p.write(CSR.MCOUNTINHIBIT, 5)
      for ((a, n) <- Seq((CSR.MCYCLE, BigInt("ffffffff", 16)), (CSR.MCYCLEH, BigInt(3)),
        (CSR.MINSTRET, BigInt("fffffffe", 16)), (CSR.MINSTRETH, BigInt(7)))) p.write(a, n)
      val reads = Seq(CSR.MCYCLE, CSR.MCYCLEH, CSR.MINSTRET, CSR.MINSTRETH,
        CSR.CYCLE, CSR.CYCLEH, CSR.INSTRET, CSR.INSTRETH)
      reads.zipWithIndex.foreach { case (a, i) => read(p, a, 128 + 4 * i) }
      p.write(CSR.MHPMCOUNTER3, 99); p.write(CSR.MHPMCOUNTER3H, 2)
      p.write(CSR.MHPMEVENT3, 0); p.write(CSR.MHPMEVENT31, 17)
      read(p, CSR.MHPMCOUNTER3, 160); read(p, CSR.MHPMCOUNTER3H, 164)
      read(p, CSR.HPMCOUNTER31, 168); read(p, CSR.MHPMEVENT31, 172); p.done()
      val halves = Seq[BigInt](BigInt("ffffffff", 16), 3, BigInt("fffffffe", 16), 7)
      add("CT01", "64位计数器写入/高低半/禁止增长/固定零HPM槽", "counter", "8.6",
        "§8.3新增（教材Counters未测）", p,
        (halves ++ halves).zipWithIndex.map { case (n, i) => Write(128 + 4 * i, n) } ++
        Seq(Write(160, if (v.hpm > 0) 99 else 0), Write(164, if (v.hpm > 0) 2 else 0),
          Write(168, 0), Write(172, 0)))
    }
    for (mode <- Seq(0, 1) if mode == 0 || v.s; allowed <- Seq(false, true)) {
      val p = program; setup(p); p.write(CSR.MCOUNTINHIBIT, 5); p.write(CSR.MCYCLE, 123)
      p.write(CSR.MCOUNTEREN, if (allowed) 1 else 0)
      if (v.s) p.write(CSR.SCOUNTEREN, if (allowed) 1 else 0)
      p.enter("lower", mode); p.label("lower")
      val pc = p.pc; val word = csr(6, CSR.CYCLE, 0)
      p.emit(addi(6, 0, 0x77)); val faultPC = p.pc
      p.emit(word, Nop, store(6, 128)); p.done()
      p.org(v.reset + 1536); logger(p)
      add(s"CT${if (allowed) "A" else "D"}$mode", s"mode=$mode counteren许可=$allowed",
        "counter", "8.6", "WALLY-csr-permission-u-01/WALLY-csr-permission-s-01", p,
        (if (allowed) Nil else trap(faultPC, 2, word, mode << 11)) ++
          Seq(Write(128, if (allowed) 123 else 0x77)))
    }
    // WFI timeout and TW restrictions, plus local wake with global delivery disabled.
    for (mode <- Seq(3, 1, 0) if mode != 1 || v.s; tw <- Seq(false, true)
      if mode != 0 || tw) {
      val p = program; setup(p)
      if (mode != 3) { p.enter("lower", mode, if (tw) BigInt(1) << 21 else 0); p.label("lower") }
      else if (tw) p.write(CSR.MSTATUS, BigInt(1) << 21)
      val pc = p.pc; p.emit(Wfi, Nop, addi(5, 0, 42), store(5, 128)); p.done()
      p.org(v.reset + 1536); logger(p)
      val illegal = mode != 3 && (tw || mode == 0)
      val status = (if (tw) BigInt(1) << 21 else BigInt(0)) | (mode << 11)
      add(s"WF$mode${if (tw) 1 else 0}", s"WFI有界等待 mode=$mode TW=$tw", "privileged",
        "8.5/8.7", "WALLY-wfi-01/WALLY-status-tw-01", p,
        (if (illegal) trap(pc, 2, Wfi, status) else Nil) ++ Seq(Write(128, 42)))
    }
    {
      val p = program; p.write(CSR.MIE, 128); p.emit(store(0, Raise), Wfi)
      p.emit(addi(5, 0, 42), store(5, 128), store(0, Ack)); p.done()
      add("WFWAKE", "局部使能唤醒WFI、全局屏蔽阻止陷阱", "interrupt", "8.5/8.9",
        "WALLY-wfi-01/WALLY-status-mie-01", p,
        Seq(Write(Raise, 0), Write(128, 42), Write(Ack, 0)), Stimulus(pins = 4, delayed = true))
    }
    // Six interrupt causes, direct/vectored and S delegation. Software bits are set by CSR.
    for (cause <- Seq(1, 3, 5, 7, 9, 11) if v.s || Set(3, 7, 11)(cause);
      delegated <- (if (Set(1, 5, 9)(cause)) Seq(false, true) else Seq(false));
      vector <- Seq(false, true)) {
      val p = program; val bit = 1 << cause
      val pins = cause match { case 3 => 8; case 7 => 4; case 9 => 2; case 11 => 1; case _ => 0 }
      p.li(28, TrapLog)
      p.vector("vector", if (delegated) CSR.STVEC else CSR.MTVEC, vector)
      if (delegated) p.write(CSR.MIDELEG, bit)
      p.write(CSR.MIE, bit)
      if (pins == 0) p.write(CSR.MIP, bit)
      if (delegated) { p.enter("lower", 1, 2); p.label("lower") }
      else p.write(CSR.MSTATUS, 0x1888)
      if (pins != 0) p.emit(store(0, Raise), Wfi)
      p.label("boundary"); p.emit(addi(5, 0, 0x49), store(5, 128)); p.done()
      p.org(v.reset + 1536); p.label("vector")
      if (vector) { p.emit(store(0, 180)); p.org(v.reset + 1536 + 4 * cause) }
      logger(p, s = delegated, skip = 0, clear = pins == 0, pins = pins)
      add(s"IR$cause${if (delegated) "S" else "M"}${if (vector) "V" else "D"}",
        s"cause=$cause ${if (delegated) "S委托" else "M处理"} ${if (vector) "向量" else "直接"}",
        "interrupt", "8.9", "WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01", p,
        (if (pins != 0) Seq(Write(Raise, 0)) else Nil) ++
          trap(p.address("boundary"), (BigInt(1) << 31) | cause, 0,
            if (delegated) 0x120 else 0x1880, bit) ++
          (if (pins != 0) Seq(Write(Ack, 0)) else Nil) ++ Seq(Write(128, 0x49)),
        Stimulus(pins = pins, delayed = pins != 0))
    }
    if (v.s) {
      for (delegated <- Seq(false, true)) {
        val p = program; p.li(28, TrapLog)
        p.vector("handler", if (delegated) CSR.STVEC else CSR.MTVEC)
        if (delegated) p.write(CSR.MIDELEG, 2)
        p.write(CSR.MIE, 2); p.write(CSR.MIP, 2)
        p.emit(addi(6, 0, 0), addi(7, 0, 2))
        if (delegated) { p.enter("first", 1, 2); p.label("first") }
        else { p.write(CSR.MSTATUS, 0x1888); p.label("first") }
        p.emit(store(0, 180)); p.label("second"); p.emit(addi(5, 0, 0x61), store(5, 128)); p.done()
        p.org(v.reset + 1536); p.label("handler")
        val epc = if (delegated) CSR.SEPC else CSR.MEPC
        p.emit(addi(6, 6, 1), csr(20, epc, 0),
          csr(21, if (delegated) CSR.SCAUSE else CSR.MCAUSE, 0),
          csr(22, if (delegated) CSR.STVAL else CSR.MTVAL, 0),
          csr(23, if (delegated) CSR.SSTATUS else CSR.MSTATUS, 0),
          csr(24, if (delegated) CSR.SIP else CSR.MIP, 0))
        (20 to 24).foreach(r => p.emit(store(r, 4 * (r - 20), base = 28)))
        p.emit(addi(28, 28, 20)); p.emit(branch(6, 7, 20))
        p.la(20, "second"); p.emit(csr(0, epc, 20, 1), if (delegated) Sret else Mret)
        p.emit(csr(0, if (delegated) CSR.SIP else CSR.MIP, 0, 1), if (delegated) Sret else Mret)
        val flag = BigInt("80000001", 16)
        add(s"IRRE${if (delegated) "S" else "M"}", "pending保持、返回重入、第二次清除", "interrupt",
          "8.9", "WALLY-trap-s-01", p,
          trap(p.address("first"), flag, 0, if (delegated) 0x120 else 0x1880, 2) ++
            trap(p.address("second"), flag, 0, if (delegated) 0x120 else 0x1880, 2, 1) ++
            Seq(Write(128, 0x61)))
      }
      {
        val p = program; p.write(CSR.MIDELEG, 0x222); p.write(CSR.MIE, 0xaaa)
        p.write(CSR.MIP, 0x222); read(p, CSR.MIP, 128); read(p, CSR.SIP, 132)
        p.write(CSR.SIE, 2); read(p, CSR.MIE, 136); read(p, CSR.SIE, 140)
        p.write(CSR.SIP, 0); read(p, CSR.MIP, 144); read(p, CSR.SIP, 148)
        p.write(CSR.MIP, 0); p.done()
        add("IRVIEW", "M/S共享中断视图、委托在M模式屏蔽及SIP写掩码", "interrupt", "8.6/8.9",
          "WALLY-sie-01/WALLY-status-sie-01", p,
          Seq(0x222, 0x222, 0x88a, 2, 0x220, 0x220).zipWithIndex.map {
            case (n, i) => Write(128 + 4 * i, n) })
      }
    }
    // Local/global interrupt gates and delivery across privilege levels.
    if (v.s) {
      for (local <- Seq(false, true)) {
        val p = program; p.write(CSR.MIE, if (local) 2 else 0)
        p.write(CSR.MIP, 2); p.write(CSR.MSTATUS, if (local) 0 else 8)
        read(p, CSR.MIP, 128); p.emit(Wfi); read(p, CSR.MIP, 132)
        p.write(CSR.MIP, 0); p.done()
        add(s"IRMASK${if (local) 1 else 0}", "局部/全局屏蔽时pending保持且不进入陷阱", "interrupt",
          "8.9", "WALLY-mie-01/WALLY-status-mie-01", p, Seq(Write(128, 2), Write(132, 2)))
      }
      for (targetS <- Seq(false, true)) {
        val p = program; p.li(28, TrapLog)
        p.vector("handler", if (targetS) CSR.STVEC else CSR.MTVEC)
        if (targetS) p.write(CSR.MIDELEG, 2)
        p.write(CSR.MIE, 2); p.write(CSR.MIP, 2)
        p.enter("boundary", 0); p.label("boundary")
        p.emit(addi(5, 0, 0x55), store(5, 128)); p.done()
        p.org(v.reset + 1536); logger(p, s = targetS, skip = 0, clear = true)
        add(s"IRCROSS${if (targetS) "S" else "M"}", "U模式到更高权限中断不依赖其全局使能", "interrupt",
          "8.9", "WALLY-trap-u-01/WALLY-status-sie-01", p,
          trap(p.address("boundary"), BigInt("80000001", 16), 0, 0, 2) ++ Seq(Write(128, 0x55)))
      }
    }
    for ((suffix, pins, selected) <- Seq(("ALL", 13, 11), ("MT", 12, 3), ("T", 4, 7))) {
      val p = program; p.li(28, TrapLog); p.vector("handler"); p.write(CSR.MIE, 0xaaa)
      p.emit(store(0, Raise), Wfi)
      if (v.s) p.write(CSR.MIP, 0x222)
      p.write(CSR.MSTATUS, 0x1888); p.label("boundary")
      p.emit(addi(5, 0, 0x56), store(5, 128)); p.done()
      p.org(v.reset + 1536); logger(p, skip = 0, clear = true, pins = pins)
      val pending = (if (v.s) 0x222 else 0) | (if ((pins & 1) != 0) 0x800 else 0) |
        (if ((pins & 4) != 0) 0x80 else 0) | (if ((pins & 8) != 0) 8 else 0)
      add(s"IRPRI$suffix", "同时pending的MEI > MSI > MTI > S类优先级", "interrupt", "8.9",
        "WALLY-trap-01", p, Seq(Write(Raise, 0)) ++
          trap(p.address("boundary"), (BigInt(1) << 31) | selected, 0, 0x1880, pending) ++
          Seq(Write(Ack, 0), Write(128, 0x56)), Stimulus(pins = pins, delayed = true))
    }
    if (v.s) {
      for ((bits, selected) <- Seq((0x222, 9), (0x22, 1), (32, 5))) {
        val p = program; setup(p); p.write(CSR.MIE, bits); p.write(CSR.MIP, bits)
        p.write(CSR.MSTATUS, 0x1888); p.label("boundary")
        p.emit(addi(5, 0, 0x57), store(5, 128)); p.done()
        p.org(v.reset + 1536); logger(p, skip = 0, clear = true)
        add(s"IRPRIS$selected", "S类pending优先级SEI > SSI > STI", "interrupt", "8.9", "WALLY-trap-01", p,
          trap(p.address("boundary"), (BigInt(1) << 31) | selected, 0, 0x1880, bits) ++ Seq(Write(128, 0x57)))
      }
    }
    {
      val p = program; setup(p); p.emit(addi(1, 0, 5), addi(2, 0, 0))
      p.write(CSR.MCOUNTINHIBIT, 5, rs = 3); p.write(CSR.MINSTRET, 0, rs = 3)
      p.write(CSR.MINSTRETH, 0, rs = 3)
      p.emit(csr(0, CSR.MCOUNTINHIBIT, 2, 1), addi(5, 0, 41), store(5, 0), load(6, 0),
        addi(7, 6, 1), Wfi)
      val pc = p.pc; val word = BigInt("ffffffff", 16)
      p.emit(word, store(5, 180), store(7, 4), csr(0, CSR.MCOUNTINHIBIT, 1, 1))
      read(p, CSR.MINSTRET, 128); read(p, CSR.MINSTRETH, 132); p.done()
      p.org(v.reset + 1536); logger(p)
      add("CTRET", "异常不退休、load停顿和WFI只退休一次", "counter", "8.6", "§8.3新增", p,
        Seq(Write(0, 41)) ++ trap(pc, 2, word, 0x1800) ++
          Seq(Write(4, 42), Write(128, 21), Write(132, 0)))
    }
    {
      val p = program; p.emit(addi(1, 0, 5), addi(2, 0, 4))
      p.write(CSR.MCOUNTINHIBIT, 5, rs = 3)
      p.write(CSR.MCYCLE, BigInt("ffffffff", 16), rs = 3); p.write(CSR.MCYCLEH, 3, rs = 3)
      p.emit(csr(0, CSR.MCOUNTINHIBIT, 2, 1), Wfi, csr(0, CSR.MCOUNTINHIBIT, 1, 1))
      read(p, CSR.MCYCLEH, 128)
      p.emit(csr(5, CSR.MCYCLE, 0))
      // SLTIU proves the low half stayed in the independent bounded range after carry.
      p.emit((BigInt(200) << 20) | (BigInt(5) << 15) | (BigInt(3) << 12) | (BigInt(6) << 7) | 0x13,
        store(6, 132))
      p.write(CSR.MINSTRET, BigInt("fffffffe", 16), rs = 3); p.write(CSR.MINSTRETH, 7, rs = 3)
      p.emit(addi(2, 0, 1), csr(0, CSR.MCOUNTINHIBIT, 2, 1), addi(5, 0, 1), addi(6, 0, 2),
        csr(0, CSR.MCOUNTINHIBIT, 1, 1))
      read(p, CSR.MINSTRET, 136); read(p, CSR.MINSTRETH, 140); p.done()
      add("CTCARRY", "cycle/instret低半溢出向高半进位", "counter", "8.6", "§8.3新增", p,
        Seq(Write(128, 4), Write(132, 1), Write(136, 1), Write(140, 8)))
    }
    {
      val p = program; read(p, CSR.TIME, 128); read(p, CSR.TIMEH, 132); p.done()
      add("CTTIME", "外部64位TIME的高低半读视图", "counter", "8.6", "§8.3新增", p,
        Seq(Write(128, BigInt("2468acf0", 16)), Write(132, BigInt("12345678", 16))),
        Stimulus(timeInitial = BigInt("123456782468acf0", 16)))
    }
    if (v.hpm > 0) {
      for (event <- 1 to 5) {
        val p = program
        p.emit(addi(1, 0, 61), addi(2, 0, 0), addi(5, 0, 41))
        p.write(CSR.MCOUNTINHIBIT, 61, rs = 3)
        p.write(CSR.MHPMCOUNTER3, 0, rs = 3); p.write(CSR.MHPMCOUNTER3H, 0, rs = 3)
        p.write(CSR.MHPMEVENT3, event, rs = 3)
        p.emit(csr(0, CSR.MCOUNTINHIBIT, 2, 1), store(5, 0), load(6, 0),
          addi(7, 6, 1), store(7, 4), load(8, 0, 4), addi(9, 8, 1),
          csr(0, CSR.MCOUNTINHIBIT, 1, 1))
        // Keep canceled younger CSR dependencies out of the raw LoadStallD event source.
        p.emit(Nop, Nop)
        read(p, CSR.MHPMCOUNTER3, 128); read(p, CSR.MHPMCOUNTER3H, 132); p.done()
        val counts = Seq(7, 2, 2, 2, 2)
        add(s"HPM$event", s"HPM事件$event（退休/加载/存储/load-use/存储加载冲突）", "counter",
          "8.6", "§8.3新增", p, Seq(Write(0, 41), Write(4, 42), Write(128, counts(event - 1)), Write(132, 0)))
      }
    }
    // Data endian selection in each mode and MPRV U/S; instruction fetch stays little endian.
    for (mode <- Seq(3, 0, 1) if mode != 1 || v.s; mprv <- Seq(false, true)
      if !mprv || mode != 3) {
      val p = program; p.li(1, BigInt("11223344", 16)); p.emit(store(1, 0))
      p.write(CSR.MSTATUSH, if (v.be) 0x30 else 0x30)
      if (mprv) p.write(CSR.MSTATUS, (BigInt(1) << 17) | (mode << 11) | 0x40)
      else if (mode != 3) { p.enter("lower", mode, 0x40); p.label("lower") }
      p.emit(load(5, 0), load(6, 0, 0, 4), load(7, 0, 0, 5),
        store(5, 128), store(6, 132), store(7, 136)); p.done()
      add(s"EN$mode${if (mprv) "P" else "N"}", s"数据端序 mode=$mode MPRV=$mprv", "endian",
        "8.7", "WALLY-endianness-01", p,
        Seq(Write(0, BigInt("11223344", 16)),
          Write(128, if (v.be) BigInt("44332211", 16) else BigInt("11223344", 16)),
          Write(132, 0x44), Write(136, if (v.be) 0x4433 else 0x3344)))
    }
    if (v.stc) {
      val p = program; p.write(CSR.STIMECMPH, 1); p.write(CSR.STIMECMP, 16)
      p.write(CSR.MIP, 32); read(p, CSR.MIP, 128)
      p.write(CSR.MENVCFGH, BigInt("80000000", 16)); read(p, CSR.MIP, 132)
      p.write(CSR.MIP, 32); p.write(CSR.MENVCFGH, 0); read(p, CSR.MIP, 136)
      p.write(CSR.MENVCFGH, BigInt("80000000", 16)); read(p, CSR.MIP, 140)
      p.write(CSR.STIMECMPH, 2); read(p, CSR.MIP, 144)
      p.write(CSR.MCOUNTEREN, 2); p.enter("lower", 1); p.label("lower")
      read(p, CSR.STIMECMP, 148); read(p, CSR.STIMECMPH, 152); p.done()
      add("ST01", "64位Sstc比较、STCE选择/写屏蔽及S访问", "optional", "8.9",
        "WALLY-trap-01/WALLY-TEST-LIB-32.h", p,
        Seq(32, 32, 0, 32, 0, 16, 2).zipWithIndex.map { case (n, i) => Write(128 + 4 * i, n) },
        Stimulus(timeInitial = BigInt("100000020", 16)))
      locally {
        val p = program; p.li(28, TrapLog); p.vector("handler", CSR.STVEC)
        p.write(CSR.STIMECMPH, 1); p.write(CSR.STIMECMP, 16)
        p.write(CSR.MENVCFGH, BigInt("80000000", 16)); p.write(CSR.MCOUNTEREN, 2)
        p.write(CSR.MIDELEG, 32); p.write(CSR.MIE, 32)
        p.enter("lower", 1, 2); p.label("lower"); p.emit(store(0, Raise), Wfi)
        p.label("boundary"); p.emit(addi(5, 0, 0x58), store(5, 128)); p.done()
        p.org(v.reset + 1536); logger(p, s = true, skip = 0, pins = 16)
        add("STIRQ", "Sstc时间越过64位比较值、S委托陷阱并撤销pending", "optional", "8.9",
          "WALLY-trap-s-01/WALLY-TEST-LIB-32.h", p, Seq(Write(Raise, 0)) ++
          trap(p.address("boundary"), BigInt("80000005", 16), 0, 0x120, 32) ++
          Seq(Write(Ack, 0), Write(128, 0x58)),
          Stimulus(timeRaised = BigInt("100000020", 16), delayed = true))
      }
      for (stce <- Seq(false, true)) {
        val p = program; setup(p)
        p.write(CSR.MCOUNTEREN, if (stce) 0 else 2)
        p.write(CSR.MENVCFGH, if (stce) BigInt("80000000", 16) else 0)
        p.enter("lower", 1); p.label("lower")
        val pc = p.pc; val word = csr(6, CSR.STIMECMP, 0)
        p.emit(word, Nop); p.done(); p.org(v.reset + 1536); logger(p)
        add(s"STPER${if (stce) 1 else 0}", "Sstc访问须同时满足STCE和TM", "optional", "8.9",
          "WALLY-csr-permission-s-01", p, trap(pc, 2, word, 0x800, if (stce) 32 else 0))
      }
    }
    out.toSeq
  }
  lazy val all: Seq[Case] = variants.flatMap(cases)

  def writeText(path: Path, value: String): Unit = {
    Files.createDirectories(path.getParent)
    Files.write(path, value.getBytes(StandardCharsets.UTF_8))
  }
}

/** Test-scope exporter keeps fixtures out of production classes. */
object ExportChapter8TestPlan extends App {
  import Chapter8TestPlanCases._
  val rows = all.map { c =>
    writeText(Root.resolve(s"cases/${c.key}.events"), c.expected.map { w =>
      Seq(w.address, w.data, w.mask).map(n => f"$n%08x").mkString(" ")
    }.mkString("", "\n", "\n"))
    Seq(c.variant.name, c.id, c.config.instructionInitFile.get,
      s"${Root.toString}/cases/${c.key}.events", c.limit.toString,
      c.stimulus.pins.toString, c.stimulus.timeInitial.toString,
      c.stimulus.timeRaised.toString, c.stimulus.delayed.toString).mkString("\t")
  }
  writeText(Root.resolve("manifest.tsv"),
    "config\tid\timage\tevents\tlimit\tpins\ttimeInitial\ttimeRaised\tdelayed\n" + rows.mkString("", "\n", "\n"))
  val metadata = all.map(c => ujson.Obj("key" -> c.key, "id" -> c.id,
    "config" -> c.variant.name, "title" -> c.title, "category" -> c.category,
    "book" -> c.book, "cvw" -> c.cvw, "limit" -> c.limit,
    "expected" -> ujson.Arr.from(c.expected.map(w => ujson.Obj(
      "address" -> w.address.toString, "data" -> w.data.toString, "mask" -> w.mask.toString)))))
  writeText(Root.resolve("cases.json"), ujson.write(ujson.Arr.from(metadata), indent = 2))
  println(s"Exported ${all.size} independent cases to $Root")
}
