package riscvpipeline

import chisel3._
import chiseltest._
import org.scalatest.ParallelTestExecution
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.ArrayBuffer

import Chapter8Programs.Write
import Chapter8TestPlanCases._

/** 用真实指令验证整机，只驱动输入、观察存储端口，不修改DUT内部状态。 */
class Chapter8TestPlanSpec extends AnyFlatSpec with ChiselScalatestTester with ParallelTestExecution {
  override def newInstance: Chapter8TestPlanSpec = new Chapter8TestPlanSpec

  private val ResetCycles = 3
  private val ResetRepeats = 2
  private val StimulusDelayCycles = 10
  private val PostCompletionCycles = 8

  behavior of "Chapter 8.3 shared architectural test plan"

  // 过滤器匹配完整的“配置-编号”，例如 default-EX02；不设置时运行全部用例。
  private val caseFilter = sys.env.get("CHAPTER8_CASE_FILTER")
  for (testCase <- all if caseFilter.forall(testCase.key.matches)) {
    it should s"${testCase.key}: ${testCase.title}" in {
      val workDirectory = Root.resolve(s"scala-work/${testCase.key}")
      test(new Chapter8Harness(testCase.config))
        .withAnnotations(Seq(firrtl.options.TargetDirAnnotation(
          java.nio.file.Paths.get("").toAbsolutePath.normalize.relativize(
            workDirectory.toAbsolutePath.normalize).toString))) { dut =>
          // 两次运行共用同一个DUT，检查复位后的恢复能力；夹具自行初始化要读取的DTIM数据。
          for (repeat <- 0 until ResetRepeats) {
            new CaseRun(dut, testCase, repeat).run()
          }
        }
    }
  }

  /** 每次复位运行独立持有激励和检查器状态，避免不同用例或重复运行相互影响。 */
  private final class CaseRun(dut: Chapter8Harness, testCase: Case, repeat: Int) {
    private val actualStores = ArrayBuffer.empty[ujson.Value]
    private val functionalBins = scala.collection.mutable.LinkedHashMap.empty[String, ujson.Obj]
    private var previousRetiredWasWfi = false
    private var previousPending = BigInt(0)
    private var activePins = 0
    private var currentTime = testCase.stimulus.timeInitial
    private var nextExpectedStore = 0
    private var cycle = 0
    private var raiseAtCycle = -1
    private var completedAtCycle = -1
    private var failure: Option[String] = None

    def run(): Unit = {
      try {
        resetDut()
        while (shouldContinue) {
          applyScheduledStimulus()
          driveInputs()
          observeFunctionalEvents()
          observeStoreBeforeCommit()
          dut.clock.step()
          cycle += 1
        }
        checkCompletion()
      } catch {
        case scala.util.control.NonFatal(error) =>
          failure = Some(s"simulation/assertion failure: ${error.getMessage}")
          throw error
      } finally writeResult()
      assert(failure.isEmpty, s"${testCase.key} repeat=$repeat: ${failure.getOrElse("")}")
    }

    private def resetDut(): Unit = {
      driveInputs()
      dut.reset.poke(true.B)
      dut.clock.step(ResetCycles)
      dut.reset.poke(false.B)
    }

    // cycle是即将提交的上升沿编号。完成沿本身也占一拍，之后还需完整观察8拍。
    private def observationEndCycle: Int = completedAtCycle + 1 + PostCompletionCycles

    private def shouldContinue: Boolean =
      cycle < testCase.limit && failure.isEmpty &&
        (completedAtCycle < 0 || cycle < observationEndCycle)

    private def applyScheduledStimulus(): Unit = {
      if (cycle == raiseAtCycle) {
        activePins = testCase.stimulus.pins
        currentTime = testCase.stimulus.timeRaised
      }
    }

    private def driveInputs(): Unit = {
      // pins的低四位依次表示M外部、S外部、M定时器、M软件中断。
      dut.interrupts.MExtInt.poke(((activePins & 1) != 0).B)
      dut.interrupts.SExtInt.poke(((activePins & 2) != 0).B)
      dut.interrupts.MTimerInt.poke(((activePins & 4) != 0).B)
      dut.interrupts.MSwInt.poke(((activePins & 8) != 0).B)
      dut.interrupts.TIME_REGW.poke(currentTime.U)
    }

    private def observeStoreBeforeCommit(): Unit = {
      if (dut.io.MemWrite.peek().litToBoolean) {
        val store = Write(
          dut.io.IEUAdr.peek().litValue,
          dut.io.WriteData.peek().litValue,
          dut.io.ByteMask.peek().litValue
        )
        recordStore(store)
        checkStore(store)
        updateStimulusAfterStore(store)
        if (store == Chapter8Programs.doneWrite) completedAtCycle = cycle
      }
    }

    /** Architectural bins are sampled only on accepted M retirement or trap;
      * hazard bins use the actual asserted microarchitectural event. Every
      * first-hit proof records the pre-edge state that produced that event.
      */
    private def observeFunctionalEvents(): Unit = {
      def value(signal: UInt): BigInt = signal.peek().litValue
      def flag(signal: Bool): Boolean = signal.peek().litToBoolean
      val retired = flag(dut.Retired)
      val trap = flag(dut.Trap)
      val interrupt = flag(dut.Interrupt)
      val valid = flag(dut.ValidM)
      val stalled = flag(dut.WFIStall)
      val loadStall = flag(dut.LoadStallD)
      val loadInE = (value(dut.MemRWE) & 2) != 0
      val pending = value(dut.Pending)
      val priorPending = previousPending
      val pendingArrival = priorPending == 0 && pending != 0
      previousPending = pending
      if (!retired && !trap && !stalled && !loadStall) return
      val instruction = value(dut.InstrM)
      val opcode = (instruction & 127).toInt
      val funct3 = ((instruction >> 12) & 7).toInt
      val mode = value(dut.Mode).toInt
      val target = value(dut.NextMode).toInt
      val status = value(dut.Status)
      val global = if (mode == 3) ((status >> 3) & 1).toInt
        else if (mode == 1) ((status >> 1) & 1).toInt else 0
      val hasPending = if (pending != 0) 1 else 0
      val endian = if (flag(dut.BigEndian)) "big" else "little"
      val proof = ujson.Obj("cycle" -> cycle, "pc" -> value(dut.PCM).toString,
        "instruction" -> instruction.toString, "validM" -> valid,
        "retired" -> retired, "trap" -> trap, "interrupt" -> interrupt,
        "cause" -> value(dut.Cause).toString, "mode" -> mode, "targetMode" -> target,
        "pending" -> pending.toString, "status" -> status.toString,
        "previousPending" -> priorPending.toString,
        "globalEnable" -> global, "wfiStall" -> stalled, "loadStall" -> loadStall,
        "loadInE" -> loadInE,
        "previousRetiredWasWfi" -> previousRetiredWasWfi,
        "endian" -> endian, "address" -> value(dut.io.IEUAdr).toString,
        "memRW" -> value(dut.io.MemRW).toString, "funct3" -> funct3)
      def hit(id: String): Unit = functionalBins.get(id) match {
        case Some(bin) => bin("count") = bin("count").num.toInt + 1
        case None => functionalBins(id) = ujson.Obj("count" -> 1, "first" -> proof)
      }
      // LoadStallD also covers late CSR results; require an actual E load so
      // that CSR interlocks cannot satisfy the load-use cross coverage.
      if (loadStall && loadInE) hit(s"stall.load_use.pending$hasPending")
      if (loadStall && loadInE && pendingArrival) hit("interrupt.arrival.load_stall")
      if (valid && instruction == Chapter8Programs.Wfi && pendingArrival)
        hit("interrupt.arrival.wfi")
      if (stalled) hit(s"wfi.stall.mode$mode.pending$hasPending")
      if (retired || trap) {
        hit(s"privilege.mode$mode")
        hit(s"interrupt.pending$hasPending.global$global.accepted${if (interrupt) 1 else 0}.mode$mode")
      }
      if (trap) {
        hit(s"trap.cause${value(dut.Cause)}.from$mode.to$target.interrupt${if (interrupt) 1 else 0}")
        if (interrupt && previousRetiredWasWfi) hit(s"interrupt.after_wfi.mode$mode")
      }
      if (retired) {
        val kind = opcode match {
          case 3 => "load"
          case 35 => "store"
          case 99 => "branch"
          case 103 => "jalr"
          case 111 => "jal"
          case 19 | 51 => "alu"
          case 55 => "lui"
          case 23 => "auipc"
          case 15 => "fence"
          case 115 if funct3 != 0 => "csr"
          case 115 => "system"
          case _ => "unknown"
        }
        hit(s"instruction.$kind")
        if (opcode == 115 && funct3 != 0) {
          hit(s"csr.op$funct3")
          if (((instruction >> 15) & 31) == 0) hit(s"csr.op$funct3.source_zero")
          if (((instruction >> 7) & 31) == 0) hit(s"csr.op$funct3.rd_zero")
        }
        if (opcode == 3 || opcode == 35) {
          val width = 1 << (funct3 & 3)
          val offset = (value(dut.io.IEUAdr) & 3).toInt
          hit(s"memory.$endian.$kind.width$width.offset$offset")
          hit(s"memory.mode$mode.$endian")
        }
        if (instruction == Chapter8Programs.Wfi && pending != 0)
          hit(s"wfi.wake.mode$mode.pending1")
        previousRetiredWasWfi = instruction == Chapter8Programs.Wfi
      }
    }

    private def recordStore(store: Write): Unit = {
      actualStores += ujson.Obj(
        "cycle" -> cycle,
        "address" -> store.address.toString,
        "data" -> store.data.toString,
        "mask" -> store.mask.toString
      )
    }

    private def checkStore(store: Write): Unit = {
      // 比较全部有序存储，包括程序初始化、陷阱签名和完成标记；不能只看最终内存值。
      if (nextExpectedStore >= testCase.expected.size) {
        failure = Some(s"extra store $store")
      } else if (store != testCase.expected(nextExpectedStore)) {
        failure = Some(s"store $nextExpectedStore expected ${testCase.expected(nextExpectedStore)}, got $store")
      }
      nextExpectedStore += 1
    }

    private def updateStimulusAfterStore(store: Write): Unit = {
      // Raise由程序发出，延迟到WFI稳定等待时再激励；Ack由处理程序发出。
      if (store.address == Raise && testCase.stimulus.delayed) {
        raiseAtCycle = cycle + StimulusDelayCycles
      }
      if (store.address == Ack) {
        // 本拍输入已经设置；撤销状态在下一拍driveInputs时才送入DUT。
        activePins = 0
        currentTime = testCase.stimulus.timeInitial
      }
    }

    private def checkCompletion(): Unit = {
      if (failure.isEmpty && completedAtCycle >= 0 && cycle < observationEndCycle) {
        failure = Some("timeout: incomplete observation window")
      }
      if (failure.isEmpty && (completedAtCycle < 0 || nextExpectedStore != testCase.expected.size)) {
        failure = Some(s"timeout/missing stores: $nextExpectedStore/${testCase.expected.size} at $cycle")
      }
    }

    private def writeResult(): Unit = {
      // 先保存证据再断言：失败时仍能看到首个差异和此前全部存储。
      val result = ujson.Obj(
        "key" -> testCase.key,
        "layer" -> "scala",
        "repeat" -> repeat,
        "seed" -> 1,
        "cycles" -> cycle,
        "pass" -> failure.isEmpty,
        "failure" -> ujson.Str(failure.getOrElse("")),
        "functionalCoverage" -> ujson.Obj.from(functionalBins),
        "actual" -> ujson.Arr.from(actualStores)
      )
      writeText(Root.resolve(s"scala/${testCase.key}-r$repeat.json"), ujson.write(result, indent = 2))
    }
  }
}
