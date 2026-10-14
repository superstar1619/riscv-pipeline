package riscvpipeline

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import scala.collection.mutable.ArrayBuffer
import riscvpipeline.config.CpuConfig
import riscvpipeline.privileged.PrivilegedConfig
import TestEncoding.{CSR, CSRFunction, Privilege}

/** Scala与独立RTL仿真共用的整机用例。
  * 指令由既有手编码器生成；预期签名来自人工推导，不能由DUT输出反推。
  */
object Chapter8TestPlanCases {
  import Chapter8Programs._

  private val outputBase = Paths.get("target/chapter8-testplan")
  private val runPointer = outputBase.resolve("current-run.txt")

  // 完整报告使用独立运行目录；直接testOnly时也可以使用默认目录。
  val Root: Path =
    if (Files.exists(runPointer)) Paths.get(Files.readString(runPointer).trim) else outputBase

  // 程序通过存储标记与测试驱动握手，这两个地址不属于陷阱签名区。
  val Raise = 600
  val Ack = 604
  private val TrapHandlerOffset = 1536
  private val TrapRecordBytes = 5 * 4

  /** 四种受测配置的差异；存储深度由Program.config统一设为2048字。 */
  final case class Variant(
    name: String,
    supervisorSupported: Boolean = true,
    bigEndianSupported: Boolean = true,
    sstcSupported: Boolean = false,
    hpmCounters: Int = 0,
    resetVector: Int = 0
  ) {
    def privileged: PrivilegedConfig = PrivilegedConfig(
      supervisorSupported = supervisorSupported,
      bigEndianSupported = bigEndianSupported,
      sstcSupported = sstcSupported,
      hpmCounters = hpmCounters,
      wfiTimeoutCycles = 24
    )
  }

  val variants = Seq(
    Variant("default"),
    Variant("minimal", supervisorSupported = false, bigEndianSupported = false),
    Variant("sstc-hpm", sstcSupported = true, hpmCounters = 3),
    Variant("reset100", resetVector = 0x100)
  )

  /** pins低四位：M外部、S外部、M定时器、M软件中断。
    * delayed时等待Raise标记，再在WFI等待期间改变引脚和TIME；Ack后恢复初值。
    */
  final case class Stimulus(
    pins: Int = 0,
    timeInitial: BigInt = 0,
    timeRaised: BigInt = 0,
    delayed: Boolean = false
  )

  /** expected包含所有有序存储，末尾由createCase统一追加完成标记。 */
  final case class Case(
    id: String,
    title: String,
    category: String,
    book: String,
    cvw: String,
    variant: Variant,
    config: CpuConfig,
    expected: Seq[Write],
    stimulus: Stimulus = Stimulus(),
    limit: Int = 10000
  ) {
    val key: String = s"${variant.name}-$id"
  }

  /** 将程序镜像复制到稳定路径，使Scala和RTL加载同一份指令。 */
  private def createCase(variant: Variant, id: String, title: String, category: String,
    book: String, cvw: String, program: Program, expected: Seq[Write],
    stimulus: Stimulus = Stimulus()): Case = {
    val directory = Files.createDirectories(Root.resolve("cases"))
    val path = directory.resolve(s"${variant.name}-$id.hex")
    val generated = program.config()
    Files.copy(Paths.get(generated.instructionInitFile.get), path,
      StandardCopyOption.REPLACE_EXISTING)
    Case(id, title, category, book, cvw, variant,
      generated.copy(instructionInitFile = Some(path.toString), privileged = variant.privileged),
      expected :+ doneWrite, stimulus)
  }
  // x28作为日志指针；处理程序使用x20–x24暂存五个陷阱CSR。
  private def initializeTrapLog(program: Program): Unit = {
    program.vector("handler")
    program.li(28, TrapLog)
  }
  /** 先记录EPC/cause/tval/status/pending，再清除中断、选择返回地址并执行xRET。
    * skip默认8字节，跳过故障指令和紧随其后的poison store；中断用例通常设置skip=0。
    */
  private def emitTrapHandler(program: Program, supervisor: Boolean = false, skip: Int = 8,
    resume: Option[String] = None, clear: Boolean = false, pins: Int = 0): Unit = {
    val epc = if (supervisor) CSR.SEPC else CSR.MEPC
    program.label("handler")
    program.emit(csr(20, epc, 0), csr(21, if (supervisor) CSR.SCAUSE else CSR.MCAUSE, 0),
      csr(22, if (supervisor) CSR.STVAL else CSR.MTVAL, 0),
      csr(23, if (supervisor) CSR.SSTATUS else CSR.MSTATUS, 0),
      csr(24, if (supervisor) CSR.SIP else CSR.MIP, 0))
    for (r <- 20 to 24) program.emit(store(r, (r - 20) * 4, base = 28))
    program.emit(addi(28, 28, TrapRecordBytes))
    if (clear) {
      program.write(if (supervisor) CSR.SIP else CSR.MIP, 0)
      if (supervisor) program.write(CSR.SIE, 0) // SIP中的STIP只读，需要通过SIE屏蔽。
    }
    if (pins != 0) program.emit(store(0, Ack))
    resume match {
      case Some(label) => program.la(20, label)
      case None => if (skip != 0) program.emit(addi(20, 20, skip))
    }
    program.emit(csr(0, epc, 20, CSRFunction.RW), if (supervisor) Sret else Mret)
  }
  /** 每条陷阱记录占五个字；index用于中断返回后重入的第二条记录。 */
  private def expectedTrapWrites(pc: Int, cause: BigInt, tval: BigInt, status: BigInt,
    pending: BigInt = 0, index: Int = 0): Seq[Write] =
    Seq[BigInt](pc, cause, tval, status, pending).zipWithIndex.map {
      case (value, i) => Write(TrapLog + index * TrapRecordBytes + i * 4, value)
    }
  private def readCsrToSignature(program: Program, addr: Int, signature: Int): Unit =
    program.emit(csr(5, addr, 0), store(5, signature))

  /** 每个配置单独构建用例；调用顺序同时决定导出清单和Scala测试的顺序。 */
  def cases(variant: Variant): Seq[Case] = new CaseBuilder(variant).build()

  private final class CaseBuilder(variant: Variant) {
    private val fixtures = ArrayBuffer.empty[Case]
    private def addCase(id: String, title: String, category: String, book: String, cvw: String,
      program: Program, writes: Seq[Write], stimulus: Stimulus = Stimulus()): Unit =
      fixtures += createCase(variant, id, title, category, book, cvw, program, writes, stimulus)
    private def newProgram(): Program = new Program(variant.resetVector)

    def build(): Seq[Case] = {
      addExceptionCases()
      addPreciseCommitCases()
      addCsrReadWriteCases()
      addIllegalAccessCases()
      addCounterAccessCases()
      addWfiCases()
      addInterruptEntryCases()
      addInterruptReentryCases()
      addInterruptMaskCases()
      addInterruptPriorityCases()
      addCounterEventCases()
      addEndianCases()
      addSstcCases()
      fixtures.toSeq
    }

    /** 11类非页异常：同时检查陷阱信息、故障目的寄存器和年轻存储取消。 */
    private def addExceptionCases(): Unit = {
      for (cause <- Seq(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11) if cause != 9 || variant.supervisorSupported) {
        val program = newProgram()
        initializeTrapLog(program)
        program.emit(addi(5, 0, 0x44), addi(6, 0, 0x77))
        val mode = if (cause == 8) Privilege.USER
          else if (cause == 9) Privilege.SUPERVISOR
          else Privilege.MACHINE
        if (mode != Privilege.MACHINE) {
          program.enter("lower", mode)
          program.label("lower")
        }
        if (cause == 1 || cause == 5 || cause == 7) program.li(1, 8192)
        val pc = program.pc
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
        // 年轻存储不应提交；恢复后x6仍应保留故障前的0x77哨兵值。
        program.emit(word, store(5, 160))
        program.label("resume")
        program.emit(store(5, 128), store(6, 132))
        program.done()
        program.org(variant.resetVector + TrapHandlerOffset)
        emitTrapHandler(program, resume = Some("resume"))
        val epc = if (cause == 1) 8192 else pc
        val tval: BigInt = cause match {
          case 0 => pc + 2
          case 1 | 5 | 7 => 8192
          case 2 => word
          case 3 => pc
          case 4 | 6 => 2
          case _ => 0
        }
        // 陷阱入口MPP保存原模式；原MIE为0，因此MPIE也为0。
        val status = mode << 11
        addCase(f"EX$cause%02d", s"非页异常 cause=$cause 与精确副作用", "exception",
          "8.8", "WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01", program,
          expectedTrapWrites(epc, cause, tval, status) ++ Seq(Write(128, 0x44), Write(132, 0x77)))
      }
    }

    /** 精确提交与错误路径取消。 */
    private def addPreciseCommitCases(): Unit = {
      {
        val program = newProgram()
        initializeTrapLog(program)
        program.emit(addi(5, 0, 7), addi(6, 0, 9), addi(5, 0, 0x44))
        val pc = program.pc
        val word = csr(6, 0x777, 0)
        program.emit(word)
        program.jump("poison")
        program.emit(store(5, 160))
        program.label("resume")
        program.emit(store(5, 128), store(6, 132))
        program.done()
        program.label("poison")
        program.emit(store(5, 164), jal(0, 0))
        program.org(variant.resetVector + TrapHandlerOffset)
        emitTrapHandler(program, resume = Some("resume"))
        addCase("PR01", "老指令完成、故障rd保留、年轻跳转与存储取消", "precise", "8.3",
          "WALLY-trap-01", program, expectedTrapWrites(pc, 2, word, 0x1800) ++
          Seq(Write(128, 0x44), Write(132, 9)))
      }
      {
        val program = newProgram()
        program.emit(addi(1, 0, 1), branch(0, 1, 2))
        program.jump("safe")
        program.emit(Ebreak, BigInt("ffffffff", 16), store(1, 160))
        program.label("safe")
        program.emit(store(1, 128))
        program.done()
        addCase("PR02", "取消错误路径异常及未采取分支的未对齐目标", "precise", "8.8",
          "WALLY-trap-01", program, Seq(Write(128, 1)))
      }
    }

    /** CSR读改写、身份寄存器与WARL掩码。 */
    private def addCsrReadWriteCases(): Unit = {
      {
        val program = newProgram()
        program.emit(addi(1, 0, 0x12), csr(2, CSR.MSCRATCH, 1, CSRFunction.RW), addi(3, 2, 1),
          csr(4, CSR.MSCRATCH, 1, CSRFunction.RS), addi(1, 0, 3), csr(5, CSR.MSCRATCH, 1, CSRFunction.RC),
          csr(6, CSR.MSCRATCH, 7, CSRFunction.RWI), csr(7, CSR.MSCRATCH, 8, CSRFunction.RSI),
          csr(8, CSR.MSCRATCH, 2, CSRFunction.RCI), csr(9, CSR.MSCRATCH, 0),
          csr(0, CSR.MSCRATCH, 1, CSRFunction.RW), csr(10, CSR.MSCRATCH, 0),
          csr(11, CSR.MSCRATCH, 0, CSRFunction.RSI), csr(0, CSR.MSCRATCH, 0, CSRFunction.RCI),
          addi(1, 0, 0x55), csr(12, CSR.MSCRATCH, 1, CSRFunction.RW), csr(13, CSR.MSCRATCH, 0))
        (2 to 13).foreach(r => program.emit(store(r, 128 + 4 * (r - 2))))
        program.done()
        addCase("CS01", "六种CSR读改写、rd/rs1抑制与依赖", "csr", "8.6", "WALLY-trap-01", program,
          Seq(0, 1, 0x12, 0x12, 0x10, 7, 15, 13, 3, 3, 3, 0x55)
            .zipWithIndex.map { case (n, i) => Write(128 + 4 * i, n) })
      }
      {
        val program = newProgram()
        val addresses = Seq(CSR.MVENDORID, CSR.MARCHID, CSR.MIMPID, CSR.MHARTID,
          CSR.MCONFIGPTR, CSR.MISA)
        addresses.zipWithIndex.foreach { case (a, i) => readCsrToSignature(program, a, 128 + 4 * i) }
        program.write(CSR.MISA, 0)
        readCsrToSignature(program, CSR.MISA, 152)
        program.done()
        val misa = if (variant.supervisorSupported) BigInt("40140100", 16) else BigInt("40100100", 16)
        addCase("CS02", "身份寄存器及固定RV32 I/U/S misa", "csr", "8.6", "WALLY-minfo-01/WALLY-misa-01",
          program, (Seq[BigInt](0, 0, 0, 0, 0, misa, misa)).zipWithIndex.map {
            case (n, i) => Write(128 + 4 * i, n) })
      }
      {
        val program = newProgram()
        val controls = Seq((CSR.MEDELEG, if (variant.supervisorSupported) 0x3ff else 0),
          (CSR.MIDELEG, if (variant.supervisorSupported) 0x222 else 0),
          (CSR.MIE, if (variant.supervisorSupported) 0xaaa else 0x888),
          (CSR.MCOUNTEREN, if (variant.hpmCounters > 0) 63 else 7),
          (CSR.MCOUNTINHIBIT, if (variant.hpmCounters > 0) 61 else 5), (CSR.MENVCFG, 0))
          .filter { case (a, _) => variant.supervisorSupported || (a != CSR.MEDELEG && a != CSR.MIDELEG) }
        controls.zipWithIndex.foreach { case ((a, _), i) =>
          program.write(a, Mask)
          readCsrToSignature(program, a, 128 + 4 * i)
        }
        program.write(CSR.MIE, 0)
        program.write(CSR.MSTATUS, Mask)
        readCsrToSignature(program, CSR.MSTATUS, 152)
        program.write(CSR.MSTATUSH, Mask)
        readCsrToSignature(program, CSR.MSTATUSH, 156)
        program.write(CSR.MTVEC, 0x7ff)
        readCsrToSignature(program, CSR.MTVEC, 160)
        program.write(CSR.MEPC, 0x7ff)
        readCsrToSignature(program, CSR.MEPC, 164)
        if (variant.supervisorSupported) {
          program.write(CSR.SATP, Mask)
          readCsrToSignature(program, CSR.SATP, 168)
        }
        program.done()
        val status =
          if (variant.supervisorSupported && variant.bigEndianSupported) 0x7a19ea
          else if (variant.supervisorSupported) 0x7a19aa
          else if (variant.bigEndianSupported) 0x2218c8
          else 0x221888
        val statusHigh =
          if (!variant.bigEndianSupported) 0
          else if (variant.supervisorSupported) 48
          else 32
        addCase("CS03", "WARL掩码、EPC/TVEC对齐及Bare SATP", "csr", "8.6/8.7",
          "WALLY-mtvec-01/WALLY-mie-01/WALLY-satp-invalid-01", program,
          controls.zipWithIndex.map { case ((_, n), i) => Write(128 + 4 * i, n) } ++
          Seq(Write(152, status), Write(156, statusHigh),
            Write(160, 0x7c1), Write(164, 0x7fc)) ++ (if (variant.supervisorSupported) Seq(Write(168, 0)) else Nil))
      }
    }

    /** 通过真实MRET进入目标权限，验证非法CSR和特权指令。 */
    private def addIllegalAccessCases(): Unit = {
      val restrictions = Seq(("CS04", 3, csr(6, CSR.MVENDORID, 0, CSRFunction.RW), BigInt(0)),
        ("CS05", 0, csr(6, CSR.MSCRATCH, 0), BigInt(0)),
        ("PV01", 0, Mret, BigInt(0)), ("PV02", 0, Sret, BigInt(0)),
        ("PV03", 3, BigInt("12000073", 16), BigInt(0))) ++
        (if (variant.supervisorSupported) Seq(("CS06", 1, csr(6, CSR.MSCRATCH, 0), BigInt(0)),
          ("PV04", 1, Sret, BigInt(1) << 22), ("PV05", 1, Mret, BigInt(0)))
         else Seq(("CS07", 3, csr(6, CSR.SSTATUS, 0), BigInt(0)),
           ("CS08", 3, csr(6, CSR.MEDELEG, 0), BigInt(0)),
           ("PV06", 3, Sret, BigInt(0))))
      for ((id, mode, word, flags) <- restrictions) {
        val program = newProgram()
        initializeTrapLog(program)
        program.emit(addi(6, 0, 0x77))
        if (mode != Privilege.MACHINE) {
          program.enter("lower", mode, flags)
          program.label("lower")
        }
        val pc = program.pc
        program.emit(word, store(6, 160), store(6, 128))
        program.done()
        program.org(variant.resetVector + TrapHandlerOffset)
        emitTrapHandler(program)
        val status = flags | (mode << 11)
        addCase(id, s"权限/只读/特权指令非法：mode=$mode word=0x${word.toString(16)}",
          if (id.startsWith("CS")) "csr" else "privileged", "8.5/8.6/8.7",
          "WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01",
          program, expectedTrapWrites(pc, 2, word, status) ++ Seq(Write(128, 0x77)))
      }
    }

    /** 计数器高低半、禁止增长与U/S读取权限。 */
    private def addCounterAccessCases(): Unit = {
      {
        val program = newProgram()
        program.write(CSR.MCOUNTINHIBIT, 5)
        for ((a, n) <- Seq((CSR.MCYCLE, BigInt("ffffffff", 16)), (CSR.MCYCLEH, BigInt(3)),
          (CSR.MINSTRET, BigInt("fffffffe", 16)), (CSR.MINSTRETH, BigInt(7)))) program.write(a, n)
        val reads = Seq(CSR.MCYCLE, CSR.MCYCLEH, CSR.MINSTRET, CSR.MINSTRETH,
          CSR.CYCLE, CSR.CYCLEH, CSR.INSTRET, CSR.INSTRETH)
        reads.zipWithIndex.foreach { case (a, i) => readCsrToSignature(program, a, 128 + 4 * i) }
        program.write(CSR.MHPMCOUNTER3, 99)
        program.write(CSR.MHPMCOUNTER3H, 2)
        program.write(CSR.MHPMEVENT3, 0)
        program.write(CSR.MHPMEVENT31, 17)
        readCsrToSignature(program, CSR.MHPMCOUNTER3, 160)
        readCsrToSignature(program, CSR.MHPMCOUNTER3H, 164)
        readCsrToSignature(program, CSR.HPMCOUNTER31, 168)
        readCsrToSignature(program, CSR.MHPMEVENT31, 172)
        program.done()
        val halves = Seq[BigInt](BigInt("ffffffff", 16), 3, BigInt("fffffffe", 16), 7)
        addCase("CT01", "64位计数器写入/高低半/禁止增长/固定零HPM槽", "counter", "8.6",
          "§8.3新增（教材Counters未测）", program,
          (halves ++ halves).zipWithIndex.map { case (n, i) => Write(128 + 4 * i, n) } ++
          Seq(Write(160, if (variant.hpmCounters > 0) 99 else 0), Write(164, if (variant.hpmCounters > 0) 2 else 0),
            Write(168, 0), Write(172, 0)))
      }
      for {
        mode <- Seq(Privilege.USER, Privilege.SUPERVISOR)
        if mode == Privilege.USER || variant.supervisorSupported
        allowed <- Seq(false, true)
      } {
        val program = newProgram()
        initializeTrapLog(program)
        program.write(CSR.MCOUNTINHIBIT, 5)
        program.write(CSR.MCYCLE, 123)
        program.write(CSR.MCOUNTEREN, if (allowed) 1 else 0)
        if (variant.supervisorSupported) program.write(CSR.SCOUNTEREN, if (allowed) 1 else 0)
        program.enter("lower", mode)
        program.label("lower")
        val word = csr(6, CSR.CYCLE, 0)
        program.emit(addi(6, 0, 0x77))
        val faultPC = program.pc
        program.emit(word, Nop, store(6, 128))
        program.done()
        program.org(variant.resetVector + TrapHandlerOffset)
        emitTrapHandler(program)
        addCase(s"CT${if (allowed) "A" else "D"}$mode", s"mode=$mode counteren许可=$allowed",
          "counter", "8.6", "WALLY-csr-permission-u-01/WALLY-csr-permission-s-01", program,
          (if (allowed) Nil else expectedTrapWrites(faultPC, 2, word, mode << 11)) ++
            Seq(Write(128, if (allowed) 123 else 0x77)))
      }
    }

    /** WFI超时、TW限制以及局部唤醒与全局陷阱使能的区别。 */
    private def addWfiCases(): Unit = {
      for {
        mode <- Seq(Privilege.MACHINE, Privilege.SUPERVISOR, Privilege.USER)
        if mode != Privilege.SUPERVISOR || variant.supervisorSupported
        tw <- Seq(false, true)
        if mode != Privilege.USER || tw
      } {
        val program = newProgram()
        initializeTrapLog(program)
        if (mode != Privilege.MACHINE) {
          program.enter("lower", mode, if (tw) BigInt(1) << 21 else 0)
          program.label("lower")
        } else if (tw) program.write(CSR.MSTATUS, BigInt(1) << 21)
        val pc = program.pc
        program.emit(Wfi, Nop, addi(5, 0, 42), store(5, 128))
        program.done()
        program.org(variant.resetVector + TrapHandlerOffset)
        emitTrapHandler(program)
        val illegal = mode != Privilege.MACHINE && (tw || mode == Privilege.USER)
        val status = (if (tw) BigInt(1) << 21 else BigInt(0)) | (mode << 11)
        addCase(s"WF$mode${if (tw) 1 else 0}", s"WFI有界等待 mode=$mode TW=$tw", "privileged",
          "8.5/8.7", "WALLY-wfi-01/WALLY-status-tw-01", program,
          (if (illegal) expectedTrapWrites(pc, 2, Wfi, status) else Nil) ++ Seq(Write(128, 42)))
      }
      {
        val program = newProgram()
        program.write(CSR.MIE, 128)
        program.emit(store(0, Raise), Wfi)
        program.emit(addi(5, 0, 42), store(5, 128), store(0, Ack))
        program.done()
        addCase("WFWAKE", "局部使能唤醒WFI、全局屏蔽阻止陷阱", "interrupt", "8.5/8.9",
          "WALLY-wfi-01/WALLY-status-mie-01", program,
          Seq(Write(Raise, 0), Write(128, 42), Write(Ack, 0)), Stimulus(pins = 4, delayed = true))
      }
    }

    /** 六类中断的直接/向量入口，以及S模式委托。 */
    private def addInterruptEntryCases(): Unit = {
      for {
        cause <- Seq(1, 3, 5, 7, 9, 11)
        if variant.supervisorSupported || Set(3, 7, 11)(cause)
        delegated <- (if (Set(1, 5, 9)(cause)) Seq(false, true) else Seq(false))
        vector <- Seq(false, true)
      } {
        val program = newProgram()
        val bit = 1 << cause
        val pins = cause match {
          case 3 => 8
          case 7 => 4
          case 9 => 2
          case 11 => 1
          case _ => 0
        }
        program.li(28, TrapLog)
        program.vector("vector", if (delegated) CSR.STVEC else CSR.MTVEC, vector)
        if (delegated) program.write(CSR.MIDELEG, bit)
        program.write(CSR.MIE, bit)
        if (pins == 0) program.write(CSR.MIP, bit)
        if (delegated) {
          program.enter("lower", 1, 2)
          program.label("lower")
        } else program.write(CSR.MSTATUS, 0x1888)
        if (pins != 0) program.emit(store(0, Raise), Wfi)
        program.label("boundary")
        program.emit(addi(5, 0, 0x49), store(5, 128))
        program.done()
        program.org(variant.resetVector + TrapHandlerOffset)
        program.label("vector")
        if (vector) {
          program.emit(store(0, 180))
          program.org(variant.resetVector + TrapHandlerOffset + 4 * cause)
        }
        emitTrapHandler(program, supervisor = delegated, skip = 0, clear = pins == 0, pins = pins)
        addCase(s"IR$cause${if (delegated) "S" else "M"}${if (vector) "V" else "D"}",
          s"cause=$cause ${if (delegated) "S委托" else "M处理"} ${if (vector) "向量" else "直接"}",
          "interrupt", "8.9", "WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01", program,
          (if (pins != 0) Seq(Write(Raise, 0)) else Nil) ++
            expectedTrapWrites(program.address("boundary"), (BigInt(1) << 31) | cause, 0,
              if (delegated) 0x120 else 0x1880, bit) ++
            (if (pins != 0) Seq(Write(Ack, 0)) else Nil) ++ Seq(Write(128, 0x49)),
          Stimulus(pins = pins, delayed = pins != 0))
      }
    }

    /** 保持pending产生返回后重入，并检查M/S中断CSR视图。 */
    private def addInterruptReentryCases(): Unit = {
      if (variant.supervisorSupported) {
        for (delegated <- Seq(false, true)) {
          val program = newProgram()
          program.li(28, TrapLog)
          program.vector("handler", if (delegated) CSR.STVEC else CSR.MTVEC)
          if (delegated) program.write(CSR.MIDELEG, 2)
          program.write(CSR.MIE, 2)
          program.write(CSR.MIP, 2)
          program.emit(addi(6, 0, 0), addi(7, 0, 2))
          if (delegated) {
            program.enter("first", 1, 2)
            program.label("first")
          } else {
            program.write(CSR.MSTATUS, 0x1888)
            program.label("first")
          }
          program.emit(store(0, 180))
          program.label("second")
          program.emit(addi(5, 0, 0x61), store(5, 128))
          program.done()
          program.org(variant.resetVector + TrapHandlerOffset)
          program.label("handler")
          val epc = if (delegated) CSR.SEPC else CSR.MEPC
          program.emit(addi(6, 6, 1), csr(20, epc, 0),
            csr(21, if (delegated) CSR.SCAUSE else CSR.MCAUSE, 0),
            csr(22, if (delegated) CSR.STVAL else CSR.MTVAL, 0),
            csr(23, if (delegated) CSR.SSTATUS else CSR.MSTATUS, 0),
            csr(24, if (delegated) CSR.SIP else CSR.MIP, 0))
          (20 to 24).foreach(r => program.emit(store(r, 4 * (r - 20), base = 28)))
          program.emit(addi(28, 28, TrapRecordBytes))
          // 第一次只改返回地址，保留pending；第二次才清除pending并返回。
          program.emit(branch(6, 7, 20))
          program.la(20, "second")
          program.emit(csr(0, epc, 20, CSRFunction.RW), if (delegated) Sret else Mret)
          program.emit(csr(0, if (delegated) CSR.SIP else CSR.MIP, 0, CSRFunction.RW),
            if (delegated) Sret else Mret)
          val flag = BigInt("80000001", 16)
          addCase(s"IRRE${if (delegated) "S" else "M"}", "pending保持、返回重入、第二次清除", "interrupt",
            "8.9", "WALLY-trap-s-01", program,
            expectedTrapWrites(program.address("first"), flag, 0, if (delegated) 0x120 else 0x1880, 2) ++
              expectedTrapWrites(program.address("second"), flag, 0, if (delegated) 0x120 else 0x1880, 2, 1) ++
              Seq(Write(128, 0x61)))
        }
        {
          val program = newProgram()
          program.write(CSR.MIDELEG, 0x222)
          program.write(CSR.MIE, 0xaaa)
          program.write(CSR.MIP, 0x222)
          readCsrToSignature(program, CSR.MIP, 128)
          readCsrToSignature(program, CSR.SIP, 132)
          program.write(CSR.SIE, 2)
          readCsrToSignature(program, CSR.MIE, 136)
          readCsrToSignature(program, CSR.SIE, 140)
          program.write(CSR.SIP, 0)
          readCsrToSignature(program, CSR.MIP, 144)
          readCsrToSignature(program, CSR.SIP, 148)
          program.write(CSR.MIP, 0)
          program.done()
          addCase("IRVIEW", "M/S共享中断视图、委托在M模式屏蔽及SIP写掩码", "interrupt", "8.6/8.9",
            "WALLY-sie-01/WALLY-status-sie-01", program,
            Seq(0x222, 0x222, 0x88a, 2, 0x220, 0x220).zipWithIndex.map {
              case (n, i) => Write(128 + 4 * i, n) })
        }
      }
    }

    /** 局部/全局屏蔽及跨权限中断使能。 */
    private def addInterruptMaskCases(): Unit = {
      if (variant.supervisorSupported) {
        for (local <- Seq(false, true)) {
          val program = newProgram()
          program.write(CSR.MIE, if (local) 2 else 0)
          program.write(CSR.MIP, 2)
          program.write(CSR.MSTATUS, if (local) 0 else 8)
          readCsrToSignature(program, CSR.MIP, 128)
          program.emit(Wfi)
          readCsrToSignature(program, CSR.MIP, 132)
          program.write(CSR.MIP, 0)
          program.done()
          addCase(s"IRMASK${if (local) 1 else 0}", "局部/全局屏蔽时pending保持且不进入陷阱", "interrupt",
            "8.9", "WALLY-mie-01/WALLY-status-mie-01", program, Seq(Write(128, 2), Write(132, 2)))
        }
        for (targetS <- Seq(false, true)) {
          val program = newProgram()
          program.li(28, TrapLog)
          program.vector("handler", if (targetS) CSR.STVEC else CSR.MTVEC)
          if (targetS) program.write(CSR.MIDELEG, 2)
          program.write(CSR.MIE, 2)
          program.write(CSR.MIP, 2)
          program.enter("boundary", 0)
          program.label("boundary")
          program.emit(addi(5, 0, 0x55), store(5, 128))
          program.done()
          program.org(variant.resetVector + TrapHandlerOffset)
          emitTrapHandler(program, supervisor = targetS, skip = 0, clear = true)
          addCase(s"IRCROSS${if (targetS) "S" else "M"}", "U模式到更高权限中断不依赖其全局使能", "interrupt",
            "8.9", "WALLY-trap-u-01/WALLY-status-sie-01", program,
            expectedTrapWrites(program.address("boundary"), BigInt("80000001", 16), 0, 0, 2) ++ Seq(Write(128, 0x55)))
        }
      }
    }

    /** 多个来源同时pending时，检查M类和S类的优先级。 */
    private def addInterruptPriorityCases(): Unit = {
      for ((suffix, pins, selected) <- Seq(("ALL", 13, 11), ("MT", 12, 3), ("T", 4, 7))) {
        val program = newProgram()
        program.li(28, TrapLog)
        program.vector("handler")
        program.write(CSR.MIE, 0xaaa)
        program.emit(store(0, Raise), Wfi)
        if (variant.supervisorSupported) program.write(CSR.MIP, 0x222)
        program.write(CSR.MSTATUS, 0x1888)
        program.label("boundary")
        program.emit(addi(5, 0, 0x56), store(5, 128))
        program.done()
        program.org(variant.resetVector + TrapHandlerOffset)
        emitTrapHandler(program, skip = 0, clear = true, pins = pins)
        val pending = (if (variant.supervisorSupported) 0x222 else 0) | (if ((pins & 1) != 0) 0x800 else 0) |
          (if ((pins & 4) != 0) 0x80 else 0) | (if ((pins & 8) != 0) 8 else 0)
        addCase(s"IRPRI$suffix", "同时pending的MEI > MSI > MTI > S类优先级", "interrupt", "8.9",
          "WALLY-trap-01", program, Seq(Write(Raise, 0)) ++
            expectedTrapWrites(program.address("boundary"), (BigInt(1) << 31) | selected, 0, 0x1880, pending) ++
            Seq(Write(Ack, 0), Write(128, 0x56)), Stimulus(pins = pins, delayed = true))
      }
      if (variant.supervisorSupported) {
        for ((bits, selected) <- Seq((0x222, 9), (0x22, 1), (32, 5))) {
          val program = newProgram()
          initializeTrapLog(program)
          program.write(CSR.MIE, bits)
          program.write(CSR.MIP, bits)
          program.write(CSR.MSTATUS, 0x1888)
          program.label("boundary")
          program.emit(addi(5, 0, 0x57), store(5, 128))
          program.done()
          program.org(variant.resetVector + TrapHandlerOffset)
          emitTrapHandler(program, skip = 0, clear = true)
          addCase(s"IRPRIS$selected", "S类pending优先级SEI > SSI > STI", "interrupt", "8.9", "WALLY-trap-01", program,
            expectedTrapWrites(program.address("boundary"), (BigInt(1) << 31) | selected,
              0, 0x1880, bits) ++ Seq(Write(128, 0x57)))
        }
      }
    }

    /** 异常、停顿、WFI与退休计数；64位进位和五类HPM事件。 */
    private def addCounterEventCases(): Unit = {
      {
        val program = newProgram()
        initializeTrapLog(program)
        program.emit(addi(1, 0, 5), addi(2, 0, 0))
        program.write(CSR.MCOUNTINHIBIT, 5, rs = 3)
        program.write(CSR.MINSTRET, 0, rs = 3)
        program.write(CSR.MINSTRETH, 0, rs = 3)
        program.emit(csr(0, CSR.MCOUNTINHIBIT, 2, CSRFunction.RW), addi(5, 0, 41), store(5, 0), load(6, 0),
          addi(7, 6, 1), Wfi)
        val pc = program.pc
        val word = BigInt("ffffffff", 16)
        program.emit(word, store(5, 180), store(7, 4), csr(0, CSR.MCOUNTINHIBIT, 1, CSRFunction.RW))
        readCsrToSignature(program, CSR.MINSTRET, 128)
        readCsrToSignature(program, CSR.MINSTRETH, 132)
        program.done()
        program.org(variant.resetVector + TrapHandlerOffset)
        emitTrapHandler(program)
        // 5条正常指令 + 14条处理程序指令 + 2条恢复/抑制指令 = 21次退休。
        addCase("CTRET", "异常不退休、load停顿和WFI只退休一次", "counter", "8.6", "§8.3新增", program,
          Seq(Write(0, 41)) ++ expectedTrapWrites(pc, 2, word, 0x1800) ++
            Seq(Write(4, 42), Write(128, 21), Write(132, 0)))
      }
      {
        val program = newProgram()
        program.emit(addi(1, 0, 5), addi(2, 0, 4))
        program.write(CSR.MCOUNTINHIBIT, 5, rs = 3)
        program.write(CSR.MCYCLE, BigInt("ffffffff", 16), rs = 3)
        program.write(CSR.MCYCLEH, 3, rs = 3)
        program.emit(csr(0, CSR.MCOUNTINHIBIT, 2, CSRFunction.RW), Wfi, csr(0, CSR.MCOUNTINHIBIT, 1, CSRFunction.RW))
        readCsrToSignature(program, CSR.MCYCLEH, 128)
        program.emit(csr(5, CSR.MCYCLE, 0))
        // SLTIU检查低半进位后小于200；避免把具体流水线周期数当作架构预期。
        program.emit((BigInt(200) << 20) | (BigInt(5) << 15) | (BigInt(3) << 12) | (BigInt(6) << 7) | 0x13,
          store(6, 132))
        program.write(CSR.MINSTRET, BigInt("fffffffe", 16), rs = 3)
        program.write(CSR.MINSTRETH, 7, rs = 3)
        program.emit(addi(2, 0, 1), csr(0, CSR.MCOUNTINHIBIT, 2, CSRFunction.RW), addi(5, 0, 1), addi(6, 0, 2),
          csr(0, CSR.MCOUNTINHIBIT, 1, CSRFunction.RW))
        readCsrToSignature(program, CSR.MINSTRET, 136)
        readCsrToSignature(program, CSR.MINSTRETH, 140)
        program.done()
        addCase("CTCARRY", "cycle/instret低半溢出向高半进位", "counter", "8.6", "§8.3新增", program,
          Seq(Write(128, 4), Write(132, 1), Write(136, 1), Write(140, 8)))
      }
      {
        val program = newProgram()
        readCsrToSignature(program, CSR.TIME, 128)
        readCsrToSignature(program, CSR.TIMEH, 132)
        program.done()
        addCase("CTTIME", "外部64位TIME的高低半读视图", "counter", "8.6", "§8.3新增", program,
          Seq(Write(128, BigInt("2468acf0", 16)), Write(132, BigInt("12345678", 16))),
          Stimulus(timeInitial = BigInt("123456782468acf0", 16)))
      }
      if (variant.hpmCounters > 0) {
        for (event <- 1 to 5) {
          val program = newProgram()
          program.emit(addi(1, 0, 61), addi(2, 0, 0), addi(5, 0, 41))
          program.write(CSR.MCOUNTINHIBIT, 61, rs = 3)
          program.write(CSR.MHPMCOUNTER3, 0, rs = 3)
          program.write(CSR.MHPMCOUNTER3H, 0, rs = 3)
          program.write(CSR.MHPMEVENT3, event, rs = 3)
          program.emit(csr(0, CSR.MCOUNTINHIBIT, 2, CSRFunction.RW), store(5, 0), load(6, 0),
            addi(7, 6, 1), store(7, 4), load(8, 0, 4), addi(9, 8, 1),
            csr(0, CSR.MCOUNTINHIBIT, 1, CSRFunction.RW))
          // 两个NOP隔开年轻CSR依赖，避免把夹具自身的冒险计入原始LoadStallD事件。
          program.emit(Nop, Nop)
          readCsrToSignature(program, CSR.MHPMCOUNTER3, 128)
          readCsrToSignature(program, CSR.MHPMCOUNTER3H, 132)
          program.done()
          // 受控主体：7次退休、2次加载、2次存储、2次load-use和2次存储/加载冲突。
          val counts = Seq(7, 2, 2, 2, 2)
          addCase(s"HPM$event", s"HPM事件$event（退休/加载/存储/load-use/存储加载冲突）", "counter",
            "8.6", "§8.3新增", program, Seq(Write(0, 41), Write(4, 42), Write(128, counts(event - 1)), Write(132, 0)))
        }
      }
    }

    /** M/S/U数据端序及MPRV；取指仍为小端。 */
    private def addEndianCases(): Unit = {
      for {
        mode <- Seq(Privilege.MACHINE, Privilege.USER, Privilege.SUPERVISOR)
        if mode != Privilege.SUPERVISOR || variant.supervisorSupported
        mprv <- Seq(false, true)
        if !mprv || mode != Privilege.MACHINE
      } {
        val program = newProgram()
        program.li(1, BigInt("11223344", 16))
        program.emit(store(1, 0))
        // 同时请求MBE/SBE；关闭大端的配置应将这些WARL位保持为0。
        program.write(CSR.MSTATUSH, 0x30)
        if (mprv) program.write(CSR.MSTATUS, (BigInt(1) << 17) | (mode << 11) | 0x40)
        else if (mode != Privilege.MACHINE) {
          program.enter("lower", mode, 0x40)
          program.label("lower")
        }
        program.emit(load(5, 0), load(6, 0, 0, 4), load(7, 0, 0, 5),
          store(5, 128), store(6, 132), store(7, 136))
        program.done()
        addCase(s"EN$mode${if (mprv) "P" else "N"}", s"数据端序 mode=$mode MPRV=$mprv", "endian",
          "8.7", "WALLY-endianness-01", program,
          Seq(Write(0, BigInt("11223344", 16)),
            Write(128, if (variant.bigEndianSupported) BigInt("44332211", 16) else BigInt("11223344", 16)),
            Write(132, 0x44), Write(136, if (variant.bigEndianSupported) 0x4433 else 0x3344)))
      }
    }

    /** 可选Sstc：64位比较、STCE控制、定时中断与访问权限。 */
    private def addSstcCases(): Unit = {
      if (variant.sstcSupported) {
        val program = newProgram()
        program.write(CSR.STIMECMPH, 1)
        program.write(CSR.STIMECMP, 16)
        program.write(CSR.MIP, 32)
        readCsrToSignature(program, CSR.MIP, 128)
        program.write(CSR.MENVCFGH, BigInt("80000000", 16))
        readCsrToSignature(program, CSR.MIP, 132)
        program.write(CSR.MIP, 32)
        program.write(CSR.MENVCFGH, 0)
        readCsrToSignature(program, CSR.MIP, 136)
        program.write(CSR.MENVCFGH, BigInt("80000000", 16))
        readCsrToSignature(program, CSR.MIP, 140)
        program.write(CSR.STIMECMPH, 2)
        readCsrToSignature(program, CSR.MIP, 144)
        program.write(CSR.MCOUNTEREN, 2)
        program.enter("lower", 1)
        program.label("lower")
        readCsrToSignature(program, CSR.STIMECMP, 148)
        readCsrToSignature(program, CSR.STIMECMPH, 152)
        program.done()
        addCase("ST01", "64位Sstc比较、STCE选择/写屏蔽及S访问", "optional", "8.9",
          "WALLY-trap-01/WALLY-TEST-LIB-32.h", program,
          Seq(32, 32, 0, 32, 0, 16, 2).zipWithIndex.map { case (n, i) => Write(128 + 4 * i, n) },
          Stimulus(timeInitial = BigInt("100000020", 16)))
        locally {
          val program = newProgram()
          program.li(28, TrapLog)
          program.vector("handler", CSR.STVEC)
          program.write(CSR.STIMECMPH, 1)
          program.write(CSR.STIMECMP, 16)
          program.write(CSR.MENVCFGH, BigInt("80000000", 16))
          program.write(CSR.MCOUNTEREN, 2)
          program.write(CSR.MIDELEG, 32)
          program.write(CSR.MIE, 32)
          program.enter("lower", 1, 2)
          program.label("lower")
          program.emit(store(0, Raise), Wfi)
          program.label("boundary")
          program.emit(addi(5, 0, 0x58), store(5, 128))
          program.done()
          program.org(variant.resetVector + TrapHandlerOffset)
          emitTrapHandler(program, supervisor = true, skip = 0, pins = 16)
          addCase("STIRQ", "Sstc时间越过64位比较值、S委托陷阱并撤销pending", "optional", "8.9",
            "WALLY-trap-s-01/WALLY-TEST-LIB-32.h", program, Seq(Write(Raise, 0)) ++
            expectedTrapWrites(program.address("boundary"), BigInt("80000005", 16), 0, 0x120, 32) ++
            Seq(Write(Ack, 0), Write(128, 0x58)),
            Stimulus(timeRaised = BigInt("100000020", 16), delayed = true))
        }
        for (stce <- Seq(false, true)) {
          val program = newProgram()
          initializeTrapLog(program)
          program.write(CSR.MCOUNTEREN, if (stce) 0 else 2)
          program.write(CSR.MENVCFGH, if (stce) BigInt("80000000", 16) else 0)
          program.enter("lower", 1)
          program.label("lower")
          val pc = program.pc
          val word = csr(6, CSR.STIMECMP, 0)
          program.emit(word, Nop)
          program.done()
          program.org(variant.resetVector + TrapHandlerOffset)
          emitTrapHandler(program)
          addCase(s"STPER${if (stce) 1 else 0}", "Sstc访问须同时满足STCE和TM", "optional", "8.9",
            "WALLY-csr-permission-s-01", program, expectedTrapWrites(pc, 2, word, 0x800, if (stce) 32 else 0))
        }
      }
    }
  }
  lazy val all: Seq[Case] = variants.flatMap(cases)

  def writeText(path: Path, value: String): Unit = {
    Files.createDirectories(path.getParent)
    Files.write(path, value.getBytes(StandardCharsets.UTF_8))
  }
}

/** 仅在Test作用域导出，生产RTL不依赖测试夹具。
  * manifest供C++驱动使用，cases.json供报告器使用；二者共享同一组独立预期。
  */
object ExportChapter8TestPlan extends App {
  import Chapter8Programs.Write
  import Chapter8TestPlanCases._

  private def writeJson(store: Write): ujson.Value = ujson.Obj(
    "address" -> store.address.toString,
    "data" -> store.data.toString,
    "mask" -> store.mask.toString
  )

  private def caseJson(testCase: Case): ujson.Value = ujson.Obj(
    "key" -> testCase.key,
    "id" -> testCase.id,
    "config" -> testCase.variant.name,
    "title" -> testCase.title,
    "category" -> testCase.category,
    "book" -> testCase.book,
    "cvw" -> testCase.cvw,
    "limit" -> testCase.limit,
    "expected" -> ujson.Arr.from(testCase.expected.map(writeJson))
  )

  private def exportCase(testCase: Case): String = {
    val eventPath = Root.resolve(s"cases/${testCase.key}.events")
    // events每行依次是地址、原始写数据、字节掩码，均为八位十六进制数。
    val eventLines = testCase.expected.map { store =>
      Seq(store.address, store.data, store.mask).map(value => f"$value%08x").mkString(" ")
    }
    writeText(eventPath, eventLines.mkString("", "\n", "\n"))

    Seq(
      testCase.variant.name,
      testCase.id,
      testCase.config.instructionInitFile.get,
      eventPath.toString,
      testCase.limit.toString,
      testCase.stimulus.pins.toString,
      testCase.stimulus.timeInitial.toString,
      testCase.stimulus.timeRaised.toString,
      testCase.stimulus.delayed.toString
    ).mkString("\t")
  }

  private val manifestHeader =
    "config\tid\timage\tevents\tlimit\tpins\ttimeInitial\ttimeRaised\tdelayed\n"
  private val manifestRows = all.map(exportCase)
  writeText(Root.resolve("manifest.tsv"), manifestHeader + manifestRows.mkString("", "\n", "\n"))
  writeText(Root.resolve("cases.json"), ujson.write(ujson.Arr.from(all.map(caseJson)), indent = 2))
  println(s"Exported ${all.size} independent cases to $Root")
}
