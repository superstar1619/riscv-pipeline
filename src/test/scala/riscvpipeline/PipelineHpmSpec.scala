package riscvpipeline

import chisel3._
import chiseltest._
import chiseltest.experimental.expose
import org.scalatest.flatspec.AnyFlatSpec
import riscvpipeline.config.{BusConfig, CpuConfig}
import riscvpipeline.privileged.{InterruptInputs, PrivilegedConfig}

/** Real CPU/SoC, with observation confined to Test scope. */
private class HpmHarness(config: CpuConfig) extends Module {
  val interrupts = IO(Input(new InterruptInputs))
  val core: PipelineBase = if (config.bus.nonEmpty) {
    val soc = Module(new RiscvPipelineSoC(config))
    soc.clk := clock
    soc.reset := reset.asBool
    soc.interrupts := interrupts
    soc.core
  } else {
    val cpu = Module(new PrivilegedPipeline(config))
    cpu.clk := clock
    cpu.reset := reset.asBool
    cpu.interrupts := interrupts
    cpu
  }
  val validW = expose(core.ieu.dp.io.ValidW)
  val rdW = expose(core.ieu.dp.io.RdW)
  val resultW = expose(core.ieu.dp.ResultW)
  val registers = expose(core.ieu.dp.rf.Registers)
  val pcM = expose(core.ieu.io.PCM)
  val validM = expose(core.ieu.io.ValidM)
  val stallM = expose(core.StallM)
  val trapM = expose(core.TrapM)
  val causeM = expose(core.privileged.get.io.CauseM)
  val committedM = expose(core.lsu.io.BusCommittedM)
  val counts = expose(core.privileged.get.csr.counters.io.HPMCOUNTER_REGW)
}

class PipelineHpmSpec extends AnyFlatSpec with ProjectTest {
  import Chapter8Programs._
  import TestEncoding.{CSR, CSRFunction}

  private def config(p: Program, mode: String, ramWait: Int = 0, apbWait: Int = 0): CpuConfig = {
    val base = p.config(depth = 1024)
    base.copy(dmemDepth = 1024, privileged = PrivilegedConfig(hpmCounters = 2),
      bus = if (mode == "local") None else Some(BusConfig(
        bootRomInitFile = base.instructionInitFile, ramBytes = 1024, ramLatency = ramWait, apbLatency = apbWait,
        iromSupported = mode == "mixed", dtimSupported = mode == "mixed")))
  }

  private def start(p: Program): Unit = {
    p.write(0x320, 24) // inhibit HPM3/4 while configuring
    p.write(0x323, 2) // load selector
    p.write(0x324, 3) // store selector
    p.write(0xb03, 0)
    p.write(0xb04, 0)
    p.write(0x320, 0)
  }

  private def finish(p: Program): Unit = {
    p.write(0x320, 24)
    p.emit(csr(20, 0xb03, 0), csr(21, 0xb04, 0))
    // Reporting memory accesses must not change the frozen counts.
    p.emit(store(20, 128, base = 10), load(22, 10, 128),
      store(21, 132, base = 10), load(23, 10, 132), addi(31, 0, 1), jal(0, 0))
  }

  private def resetCpu(d: HpmHarness): Unit = {
    d.interrupts.MExtInt.poke(false.B)
    d.interrupts.SExtInt.poke(false.B)
    d.interrupts.MTimerInt.poke(false.B)
    d.interrupts.MSwInt.poke(false.B)
    d.interrupts.TIME_REGW.poke(0.U)
    d.reset.poke(true.B)
    d.clock.step(3)
    d.reset.poke(false.B)
  }

  private def run(d: HpmHarness)(tick: Int => Unit = _ => ()): Unit = {
    var cycles = 0
    while (cycles < 2500 && !(d.validW.peek().litToBoolean &&
      d.rdW.peek().litValue == 31 && d.resultW.peek().litValue == 1)) {
      tick(cycles)
      d.clock.step()
      cycles += 1
    }
    assert(cycles < 2500, "HPM program did not finish")
    d.clock.step(10)
  }

  private def expectCounts(d: HpmHarness, loads: Int, stores: Int): Unit = {
    d.registers(20).expect(loads.U)
    d.registers(21).expect(stores.U)
    d.registers(22).expect(loads.U)
    d.registers(23).expect(stores.U)
    d.counts(0).expect(loads.U)
    d.counts(1).expect(stores.U)
  }

  behavior of "Pipeline memory HPM events"

  for ((mode, ramWait, apbWait) <- Seq(("local", 0, 0)) ++
    (for (mode <- Seq("bus", "mixed"); ram <- Seq(0, 1, 3, 7); apb <- Seq(0, 3))
      yield (mode, ram, apb))) {
    it should s"count completed instructions once across reset mode=$mode RAM=$ramWait APB=$apbWait" in {
      val p = new Program(if (mode == "bus") 0x1000 else 0)
      p.li(10, if (mode == "bus") BigInt("80000000", 16) else BigInt(0x800))
      p.li(11, if (mode == "local") BigInt(0x900) else BigInt("80000100", 16))
      p.li(12, if (mode == "local") BigInt(0xa00) else BigInt(0x10000000))
      p.emit(addi(5, 0, 42))
      start(p)
      p.emit(store(5, 0, base = 10), load(6, 10),
        store(6, 0, base = 11), load(7, 11),
        store(7, 0, base = 12), load(0, 12)) // loads into x0 still count
      p.jump("afterWrongPath")
      p.emit(store(5, 4, base = 10), load(8, 10))
      p.label("afterWrongPath")
      finish(p)
      test(new HpmHarness(config(p, mode, ramWait, apbWait))) { d =>
        for (_ <- 0 until 2) {
          resetCpu(d)
          run(d) { _ => d.trapM.expect(false.B) }
          expectCounts(d, 3, 3)
          d.registers(6).expect(42.U)
          d.registers(7).expect(42.U)
        }
      }
    }
  }

  for (mode <- Seq("local", "bus", "mixed")) {
    it should s"exclude misaligned and unmapped loads and stores in $mode" in {
      val p = new Program(if (mode == "bus") 0x1000 else 0)
      p.vector("handler")
      p.li(10, if (mode == "bus") BigInt("80000000", 16) else BigInt(0x800))
      p.li(11, BigInt("40000000", 16))
      p.emit(addi(5, 0, 19))
      start(p)
      p.emit(store(5, 0, base = 10), load(6, 10),
        load(7, 10, 1), store(5, 1, base = 10),
        load(7, 11), store(5, 0, base = 11))
      finish(p)
      p.label("handler")
      p.emit(csr(25, CSR.MEPC, 0), addi(25, 25, 4),
        csr(0, CSR.MEPC, 25, CSRFunction.RW), Mret)
      test(new HpmHarness(config(p, mode, 3, 2))) { d =>
        resetCpu(d)
        var causes = Vector.empty[BigInt]
        run(d) { _ =>
          if (d.trapM.peek().litToBoolean && !d.stallM.peek().litToBoolean)
            causes :+= d.causeM.peek().litValue
        }
        assert(causes == Vector(4, 6, 5, 7))
        expectCounts(d, 1, 1)
      }
    }
  }

  for (write <- Seq(false, true); accepted <- Seq(false, true)) {
    it should s"count only completed memory on interrupt write=$write accepted=$accepted" in {
      val p = new Program
      p.vector("handler")
      p.li(10, BigInt("80000000", 16))
      p.emit(addi(5, 0, 19), store(5, 0, base = 10))
      start(p)
      p.write(CSR.MIE, 1 << 11)
      p.write(CSR.MSTATUS, 8)
      p.label("victim")
      p.emit(if (write) store(5, 4, base = 10) else load(6, 10))
      p.emit(Nop, Nop, Nop, Nop)
      p.label("report")
      finish(p)
      p.label("handler")
      // Skip the interrupted instruction; do not retry a canceled access.
      p.la(25, "report")
      p.emit(csr(0, CSR.MEPC, 25, CSRFunction.RW), Mret)
      test(new HpmHarness(config(p, "mixed", 7, 0))) { d =>
        resetCpu(d)
        var raised = false
        var trapped = false
        run(d) { _ =>
          if (!raised && d.validM.peek().litToBoolean &&
            d.pcM.peek().litValue == p.address("victim") &&
            d.committedM.peek().litToBoolean == accepted) {
            d.interrupts.MExtInt.poke(true.B)
            raised = true
          }
          if (raised && !trapped && d.trapM.peek().litToBoolean && !d.stallM.peek().litToBoolean) {
            d.committedM.expect(false.B)
            d.causeM.expect(11.U)
            trapped = true
          } else if (trapped) d.interrupts.MExtInt.poke(false.B)
        }
        assert(raised && trapped, "interrupt boundary was not exercised")
        expectCounts(d, if (accepted && !write) 1 else 0, if (accepted && write) 1 else 0)
      }
    }
  }
}
