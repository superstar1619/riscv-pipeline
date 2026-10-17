package riscvpipeline

import chisel3._
import chiseltest._
import chiseltest.experimental.expose
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import riscvpipeline.config.{BusConfig, CpuConfig}
import riscvpipeline.ebu.AHBMasterIO

private class BusPipelineHarness extends Module {
  val io = IO(new Bundle {
    val ahb = new AHBMasterIO
    val write = Output(Bool())
    val stall = Output(Bool())
    val rdW = Output(UInt(5.W))
    val validW = Output(Bool())
  })
  val dut = Module(new BusPipeline(CpuConfig(resetVector = 0x1000,
    privilegedSupported = true, bus = Some(BusConfig()))))
  dut.clk := clock
  dut.reset := reset.asBool
  dut.interrupts := 0.U.asTypeOf(dut.interrupts)
  io.ahb <> dut.ahb
  io.write := dut.io.MemWrite
  val stallM = expose(dut.StallM)
  val rdW = expose(dut.ieu.dp.io.RdW)
  val validW = expose(dut.ieu.dp.io.ValidW)
  val writeW = expose(dut.ieu.dp.io.RegWriteW)
  io.stall := stallM
  io.rdW := rdW
  io.validW := validW && writeW
}

class BusPipelineSpec extends AnyFlatSpec with ProjectTest with Matchers {
  behavior of "BusPipeline"

  for (latency <- Seq(0, 1, 3)) {
    it should s"retire a dependent load exactly once with $latency AHB wait cycles" in {
      test(new BusPipelineHarness) { dut =>
        val program = Seq("800000b7", "02a00113", "0020a023", "0000a183",
          "00118213", "0040a223", "0000006f").map(BigInt(_, 16))
        val memory = scala.collection.mutable.Map.empty[BigInt, BigInt]
        for ((word, index) <- program.zipWithIndex) memory(BigInt(0x1000 + 4 * index)) = word
        dut.io.ahb.HREADY.poke(true.B)
        dut.io.ahb.HRESP.poke(false.B)
        dut.io.ahb.HRDATA.poke(0.U)
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        var pending = Option.empty[(BigInt, Boolean)]
        var remaining = 0
        var physicalWrites = Vector.empty[(BigInt, BigInt)]
        var architectureWrites = 0
        var x4Writes = 0
        var finishedAt = Option.empty[Int]
        var cycles = 0
        while (cycles < 700 && finishedAt.forall(cycles < _ + 12)) {
          val ready = pending.isEmpty || remaining == 0
          dut.io.ahb.HREADY.poke(ready.B)
          dut.io.ahb.HRDATA.poke(pending.map { case (a, _) => memory.getOrElse(a & ~BigInt(3), BigInt(0)) }.getOrElse(BigInt(0)).U)
          if (dut.io.write.peek().litToBoolean) architectureWrites += 1
          if (dut.io.validW.peek().litToBoolean && dut.io.rdW.peek().litValue == 4) x4Writes += 1
          if (ready) {
            pending.foreach { case (address, write) =>
              if (write) {
                val data = dut.io.ahb.HWDATA.peek().litValue
                dut.io.ahb.HWSTRB.expect(15.U)
                memory(address) = data
                physicalWrites :+= (address, data)
                if (address == BigInt("80000004", 16)) finishedAt = Some(cycles)
              }
            }
            pending = if (dut.io.ahb.HTRANS.peek().litValue == 2)
              Some((dut.io.ahb.HADDR.peek().litValue, dut.io.ahb.HWRITE.peek().litToBoolean)) else None
            remaining = if (pending.nonEmpty) latency else 0
          } else remaining -= 1
          dut.clock.step()
          cycles += 1
        }
        finishedAt should not be empty
        physicalWrites shouldBe Vector((BigInt("80000000", 16), BigInt(42)),
          (BigInt("80000004", 16), BigInt(43)))
        architectureWrites shouldBe 2
        x4Writes shouldBe 1
      }
    }
  }
}
