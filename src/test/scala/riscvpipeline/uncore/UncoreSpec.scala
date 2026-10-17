package riscvpipeline.uncore

import chisel3._
import chiseltest._
import chiseltest.experimental.expose
import org.scalatest.flatspec.AnyFlatSpec
import riscvpipeline.config.BusConfig
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets

private class UncoreHarness(config: BusConfig) extends Module {
  val io = IO(new UncoreIO)
  val dut = Module(new Uncore(config))
  dut.clk := clock
  dut.reset := reset.asBool
  io <> dut.io
  val Selected = expose(dut.Selected)
}
class UncoreSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  private def boot(d: UncoreHarness): Unit = {
    d.io.ahb.HADDR.poke(0.U); d.io.ahb.HWRITE.poke(false.B)
    d.io.ahb.HWDATA.poke(0.U); d.io.ahb.HWSTRB.poke(0.U)
    d.io.ahb.HSIZE.poke(2.U); d.io.ahb.HBURST.poke(0.U)
    d.io.ahb.HPROT.poke(3.U); d.io.ahb.HTRANS.poke(0.U)
    d.io.ahb.HMASTLOCK.poke(false.B)
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    d.Selected.expect(1.U); d.io.ahb.HREADY.expect(true.B)
    d.io.ahb.HRDATA.expect(0.U); d.io.ahb.HRESP.expect(false.B)
  }
  private def transfer(d: UncoreHarness, address: BigInt, write: Boolean = false,
    data: BigInt = 0, mask: Int = 15, size: Int = 2): (BigInt, Int) = {
    d.io.ahb.HREADY.expect(true.B)
    d.io.ahb.HSIZE.poke(size.U)
    d.io.ahb.HADDR.poke(address.U); d.io.ahb.HWRITE.poke(write.B)
    d.io.ahb.HTRANS.poke(2.U); d.clock.step()
    d.io.ahb.HTRANS.poke(0.U); d.io.ahb.HWDATA.poke(data.U)
    d.io.ahb.HWSTRB.poke(mask.U)
    var waits = 0
    while (!d.io.ahb.HREADY.peek().litToBoolean && waits < 300) {
      d.clock.step(); waits += 1
    }
    assert(waits < 300)
    val response = d.io.ahb.HRDATA.peek().litValue
    d.clock.step()
    (response, waits)
  }
  behavior of "Uncore"
  for (latency <- 0 to 2) {
    it should s"route ROM RAM APB and unmapped accesses with $latency extra waits" in {
      val image = Paths.get(s"target/chapter9/modules/uncore/image-$latency.hex")
      Files.createDirectories(image.getParent)
      Files.write(image, "00100093\n00200113\n00300193\n00000013\n"
        .getBytes(StandardCharsets.US_ASCII))
      val c = BusConfig(bootRomBytes = 16, bootRomInitFile = Some(image.toString),
        ramBytes = 64, apbBytes = 8192, ramLatency = latency,
        romLatency = latency, apbLatency = latency)
      test(new UncoreHarness(c)) { d =>
        boot(d)
        assert(transfer(d, c.bootRomBase)._1 == BigInt("00100093", 16))
        assert(transfer(d, c.bootRomBase + 4)._2 == latency)
        assert(transfer(d, c.ramBase, true, BigInt("11223344", 16))._2 == latency)
        transfer(d, c.ramBase, true, BigInt("aabbccdd", 16), 5)
        assert(transfer(d, c.ramBase)._1 == BigInt("11bb33dd", 16))
        val (_, apbWaits) = transfer(d, c.apbBase, true, BigInt("76543210", 16))
        assert(apbWaits == latency + 1)
        assert(transfer(d, c.apbBase)._1 == BigInt("76543210", 16))
        assert(transfer(d, c.apbBase + 4)._1 == 1)
        assert(transfer(d, c.apbBase + 4096)._1 == 0)
        val none = transfer(d, BigInt("30000000", 16))
        assert(none == ((BigInt(0), 0)))
        d.io.ahb.HRESP.expect(false.B)
      }
    }
  }
  for (latency <- 0 to 2) {
    it should s"route real APB byte and halfword addresses with $latency extra waits" in {
      val c = BusConfig(ramBytes = 64, apbBytes = 8192, apbLatency = latency)
      test(new UncoreHarness(c)) { d =>
        boot(d)
        transfer(d, c.apbBase, true, BigInt("11223344", 16))
        transfer(d, c.apbBase + 1, true, BigInt("aaaaaaaa", 16), 2, 0)
        assert(transfer(d, c.apbBase)._1 == BigInt("1122aa44", 16))
        transfer(d, c.apbBase + 2, true, BigInt("bbccbbcc", 16), 12, 1)
        assert(transfer(d, c.apbBase)._1 == BigInt("bbccaa44", 16))
        assert(transfer(d, c.apbBase + 4)._1 == 3)
        assert(transfer(d, c.apbBase + 4097, size = 0)._1 == 0)
      }
    }
  }
  it should "hold the data-phase response owner while the address points elsewhere" in {
    val c = BusConfig(ramBytes = 64, ramLatency = 2)
    test(new UncoreHarness(c)) { d =>
      boot(d)
      transfer(d, c.ramBase, true, BigInt("decafbad", 16))
      d.io.ahb.HADDR.poke(c.ramBase.U); d.io.ahb.HWRITE.poke(false.B)
      d.io.ahb.HTRANS.poke(2.U); d.clock.step()
      d.Selected.expect(4.U)
      d.io.ahb.HADDR.poke(c.apbBase.U); d.io.ahb.HTRANS.poke(2.U)
      for (_ <- 0 until 2) {
        d.io.ahb.HREADY.expect(false.B); d.Selected.expect(4.U); d.clock.step()
      }
      d.Selected.expect(4.U); d.io.ahb.HREADY.expect(true.B)
      d.io.ahb.HRDATA.expect("hdecafbad".U); d.clock.step()
      d.Selected.expect(8.U)
      d.io.ahb.HREADY.expect(false.B)
      d.reset.poke(true.B); d.Selected.expect(8.U)
      d.clock.step(); d.Selected.expect(1.U)
      d.io.ahb.HRDATA.expect(0.U); d.io.ahb.HREADY.expect(true.B)
    }
  }
}
