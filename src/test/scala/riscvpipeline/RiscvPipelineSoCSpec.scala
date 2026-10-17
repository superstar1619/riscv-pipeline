package riscvpipeline

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import riscvpipeline.config.{BusConfig, CpuConfig}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.collection.mutable.ArrayBuffer

class RiscvPipelineSoCSpec extends AnyFlatSpec with ProjectTest {
  private val Words = Seq("800000b7", "02a00113", "0020a023", "0000a183", "00118213",
    "0040a223", "100002b7", "0042a023", "0002a303", "0060a423", "0000006f")
  private val ExpectedStores = Seq((BigInt("80000000", 16), BigInt(42)),
    (BigInt("80000004", 16), BigInt(43)), (BigInt("10000000", 16), BigInt(43)),
    (BigInt("80000008", 16), BigInt(43)))

  behavior of "RiscvPipelineSoC"
  for (mixed <- Seq(false, true); latency <- Seq(0, 2)) {
    it should s"execute CPU RAM/APB loads and stores twice mixed=$mixed latency=$latency" in {
      val directory = Files.createDirectories(Paths.get("target/chapter9/modules/soc"))
      val file = directory.resolve(s"smoke-$mixed-$latency.hex")
      Files.write(file, (Words ++ Seq.fill(1024 - Words.size)("00000013"))
        .mkString("", "\n", "\n").getBytes(StandardCharsets.US_ASCII))
      val entry = if (mixed) 0 else 0x1000
      val config = CpuConfig(imemDepth = 1024, dmemDepth = 1024, resetVector = entry,
        instructionInitFile = Some(file.toString), privilegedSupported = true,
        bus = Some(BusConfig(bootRomInitFile = Some(file.toString), ramLatency = latency,
          apbLatency = 1, iromSupported = mixed, dtimSupported = mixed)))
      test(new Chapter9Observer(config)) { d =>
        d.interrupts.MExtInt.poke(false.B)
        d.interrupts.SExtInt.poke(false.B)
        d.interrupts.MTimerInt.poke(false.B)
        d.interrupts.MSwInt.poke(false.B)
        d.interrupts.TIME_REGW.poke(0.U)
        for (_ <- 0 until 2) {
          d.reset.poke(true.B)
          d.clock.step(3)
          d.reset.poke(false.B)
          val stores = ArrayBuffer.empty[(BigInt, BigInt)]
          val physical = ArrayBuffer.empty[(BigInt, BigInt)]
          val commits = ArrayBuffer.empty[(BigInt, BigInt, BigInt)]
          var postDone = 0
          var cycles = 0
          var sawBusWait = false
          while (cycles < 600 && postDone < 10) {
            d.commit_trap.expect(false.B)
            if (d.commit_valid.peek().litToBoolean) {
              commits += ((d.commit_pc32.peek().litValue, d.commit_rd5.peek().litValue,
                d.commit_wdata32.peek().litValue))
            }
            if (d.event_valid.peek().litToBoolean &&
              Set(BigInt(3), BigInt(5)).contains(d.event_kind4.peek().litValue)) {
              d.event_mask4.expect(15.U)
              physical += ((d.event_addr32.peek().litValue, d.event_wdata32.peek().litValue))
            }
            if (d.store_valid.peek().litToBoolean) {
              d.store_mask4.expect(15.U)
              stores += ((d.store_addr32.peek().litValue, d.store_data32.peek().litValue))
            }
            sawBusWait ||= d.bus_wait.peek().litToBoolean
            if (stores.size >= ExpectedStores.size) postDone += 1
            d.clock.step()
            cycles += 1
          }
          assert(stores.toSeq == ExpectedStores, s"architecture stores=$stores cycles=$cycles")
          assert(physical.toSeq == ExpectedStores, s"physical stores=$physical")
          assert(commits.exists(_ == ((BigInt(entry + 12), BigInt(3), BigInt(42)))))
          assert(commits.exists(_ == ((BigInt(entry + 16), BigInt(4), BigInt(43)))))
          assert(commits.exists(_ == ((BigInt(entry + 32), BigInt(6), BigInt(43)))))
          assert(sawBusWait)
        }
      }
    }
  }
}
