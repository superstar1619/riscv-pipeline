package riscvpipeline

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.ParallelTestExecution
import scala.collection.mutable.ArrayBuffer
import Chapter8Programs.Write

class Chapter8TestPlanSpec extends AnyFlatSpec with ChiselScalatestTester with ParallelTestExecution {
  override def newInstance: Chapter8TestPlanSpec = new Chapter8TestPlanSpec
  import Chapter8TestPlanCases._
  behavior of "Chapter 8.3 shared architectural test plan"
  for (c <- all if sys.env.get("CHAPTER8_CASE_FILTER").forall(c.key.matches)) {
    it should s"${c.key}: ${c.title}" in {
      test(new Chapter8Harness(c.config)).withAnnotations(Seq(
        firrtl.options.TargetDirAnnotation(Root.resolve(s"scala-work/${c.key}").toString))) { d =>
        for (repeat <- 0 until 2) {
          var pins = 0
          var time = c.stimulus.timeInitial
          def inputs(): Unit = {
            d.interrupts.MExtInt.poke(((pins & 1) != 0).B)
            d.interrupts.SExtInt.poke(((pins & 2) != 0).B)
            d.interrupts.MTimerInt.poke(((pins & 4) != 0).B)
            d.interrupts.MSwInt.poke(((pins & 8) != 0).B)
            d.interrupts.TIME_REGW.poke(time.U)
          }
          inputs(); d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B)
          val actual = ArrayBuffer.empty[ujson.Value]
          var index = 0
          var cycle = 0
          var raiseAt = -1
          var doneAt = -1
          var failure: Option[String] = None
          while (cycle < c.limit && failure.isEmpty && (doneAt < 0 || cycle < doneAt + 9)) {
            if (cycle == raiseAt) { pins = c.stimulus.pins; time = c.stimulus.timeRaised }
            inputs()
            if (d.io.MemWrite.peek().litToBoolean) {
              val w = Write(d.io.IEUAdr.peek().litValue, d.io.WriteData.peek().litValue,
                d.io.ByteMask.peek().litValue)
              actual += ujson.Obj("cycle" -> cycle, "address" -> w.address.toString,
                "data" -> w.data.toString, "mask" -> w.mask.toString)
              if (index >= c.expected.size) failure = Some(s"extra store $w")
              else if (w != c.expected(index)) failure = Some(s"store $index expected ${c.expected(index)}, got $w")
              index += 1
              if (w.address == Raise && c.stimulus.delayed) raiseAt = cycle + 10
              if (w.address == Ack) { pins = 0; time = c.stimulus.timeInitial }
              if (w == Chapter8Programs.doneWrite) doneAt = cycle
            }
            d.clock.step(); cycle += 1
          }
          if (failure.isEmpty && doneAt >= 0 && cycle < doneAt + 9)
            failure = Some("timeout: incomplete observation window")
          if (failure.isEmpty && (doneAt < 0 || index != c.expected.size))
            failure = Some(s"timeout/missing stores: $index/${c.expected.size} at $cycle")
          writeText(Root.resolve(s"scala/${c.key}-r$repeat.json"), ujson.write(ujson.Obj(
            "key" -> c.key, "layer" -> "scala", "repeat" -> repeat, "seed" -> 1,
            "cycles" -> cycle, "pass" -> failure.isEmpty,
            "failure" -> ujson.Str(failure.getOrElse("")), "actual" -> ujson.Arr.from(actual)), indent = 2))
          assert(failure.isEmpty, s"${c.key} repeat=$repeat: ${failure.getOrElse("")}")
        }
      }
    }
  }
}
