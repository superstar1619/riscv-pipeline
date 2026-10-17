package riscvpipeline.ifu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import riscvpipeline.config.{BusConfig, CpuConfig}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

private class BusIFUHarness(local: Boolean, image: Option[String]) extends Module {
  val config = CpuConfig(imemDepth = 8, resetVector = if (local) 0 else 0x1000,
    privilegedSupported = true, instructionInitFile = image,
    bus = Some(BusConfig(iromSupported = local)))
  val io = IO(new IFUIO(config))
  val dut = Module(new IFU(config))
  dut.clk := clock
  dut.reset := reset.asBool
  io <> dut.io
}

class BusIFUSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  private def boot(d: BusIFUHarness): Unit = {
    d.io.RedirectM.poke(false.B)
    d.io.RedirectPCM.poke(0.U)
    d.io.PCSrcE.poke(false.B)
    d.io.IEUAdrE.poke(0.U)
    d.io.StallF.poke(true.B)
    d.io.StallD.poke(true.B)
    d.io.FlushD.poke(false.B)
    d.io.BusFlushF.poke(false.B)
    d.io.ahb.HREADY.poke(true.B)
    d.io.ahb.HRDATA.poke(0.U)
    d.io.ahb.HRESP.poke(false.B)
    d.reset.poke(true.B)
    d.clock.step(2)
    d.reset.poke(false.B)
  }
  private def release(d: BusIFUHarness): Unit = {
    d.io.StallF.poke(false.B)
    d.io.StallD.poke(false.B)
  }

  "BusIFU" should "fetch despite frontend stalls and consume a held Mem3 response with its PC" in {
    test(new BusIFUHarness(false, None)) { d =>
      boot(d)
      d.io.ahb.HTRANS.expect(2.U)
      d.io.ahb.HADDR.expect(0x1000.U)
      d.io.BusStallF.expect(true.B)
      d.clock.step()
      d.io.ahb.HREADY.poke(false.B)
      d.clock.step(3)
      d.io.BusCommittedF.expect(true.B)
      d.io.ahb.HREADY.poke(true.B)
      d.io.ahb.HRDATA.poke("h12300293".U)
      d.clock.step()
      d.io.BusStallF.expect(false.B)
      d.io.BusCommittedF.expect(false.B)
      d.io.ValidD.expect(false.B)
      d.io.ahb.HRDATA.poke("hffffffff".U)
      d.clock.step(4)
      d.io.PCF.expect(0x1000.U)
      release(d)
      d.clock.step()
      d.io.PCD.expect(0x1000.U)
      d.io.PCPlus4D.expect(0x1004.U)
      d.io.PCF.expect(0x1004.U)
      d.io.InstrD.expect("h12300293".U)
      d.io.ValidD.expect(true.B)
      d.io.InstrAccessFaultD.expect(false.B)
      d.io.ahb.HSIZE.expect(2.U)
      d.io.ahb.HBURST.expect(0.U)
      d.io.ahb.HWRITE.expect(false.B)
      d.io.ahb.HWSTRB.expect(0.U)
    }
  }

  it should "keep local synchronous instructions and metadata without an extra normal cycle" in {
    val path = Paths.get("target/chapter9/modules/bus-ifu/local.mem")
    Files.write(path, "11100293\n22200313\n33300393\n00000013\n00000013\n00000013\n00000013\n00000013\n"
      .getBytes(StandardCharsets.US_ASCII))
    test(new BusIFUHarness(true, Some(path.toString))) { d =>
      boot(d)
      release(d)
      d.clock.step()
      d.io.InstrD.expect("h11100293".U)
      d.io.PCD.expect(0.U)
      d.io.PCF.expect(4.U)
      d.io.ValidD.expect(true.B)
      d.io.BusStallF.expect(false.B)
      d.io.StallF.poke(true.B)
      d.io.StallD.poke(true.B)
      d.clock.step(5)
      d.io.InstrD.expect("h11100293".U)
      d.io.PCD.expect(0.U)
      release(d)
      d.clock.step()
      d.io.InstrD.expect("h22200313".U)
      d.io.PCD.expect(4.U)
      d.io.PCF.expect(8.U)
      d.io.ahb.HTRANS.expect(0.U)
    }
  }

  it should "cancel an unaccepted fetch and start the redirected mapped address" in {
    test(new BusIFUHarness(false, None)) { d =>
      boot(d)
      d.io.BusFlushF.poke(true.B)
      d.io.ahb.HTRANS.expect(0.U)
      d.clock.step(2)
      d.io.PCF.expect(0x1000.U)
      d.io.RedirectM.poke(true.B)
      d.io.RedirectPCM.poke("h80000020".U)
      d.clock.step()
      d.io.RedirectM.poke(false.B)
      d.io.BusFlushF.poke(false.B)
      d.io.PCF.expect("h80000020".U)
      d.io.ahb.HADDR.expect("h80000020".U)
      d.io.ahb.HTRANS.expect(2.U)
      d.io.ValidD.expect(false.B)
    }
  }

  it should "drain an accepted old fetch after a redirect and discard its stale response" in {
    test(new BusIFUHarness(false, None)) { d =>
      boot(d)
      d.clock.step()
      d.io.ahb.HREADY.poke(false.B)
      d.io.RedirectM.poke(true.B)
      d.io.RedirectPCM.poke("h80000040".U)
      d.clock.step()
      d.io.RedirectM.poke(false.B)
      d.clock.step(2)
      d.io.ahb.HREADY.poke(true.B)
      d.io.ahb.HRDATA.poke("h11100293".U)
      d.clock.step()
      release(d)
      d.clock.step() // consume and discard old response
      d.io.PCF.expect("h80000040".U)
      d.io.ValidD.expect(false.B)
      d.io.ahb.HTRANS.expect(2.U)
      d.clock.step() // target address accepted
      d.io.ahb.HRDATA.poke("h22200313".U)
      d.io.StallF.poke(true.B)
      d.io.StallD.poke(true.B)
      d.clock.step() // target Data complete
      release(d)
      d.clock.step()
      d.io.PCD.expect("h80000040".U)
      d.io.InstrD.expect("h22200313".U)
      d.io.ValidD.expect(true.B)
    }
  }

  for (address <- Seq(BigInt("10000000", 16), BigInt("40000000", 16))) {
    it should s"report an instruction access fault without fetching address ${address.toString(16)}" in {
      test(new BusIFUHarness(false, None)) { d =>
        boot(d)
        d.io.RedirectM.poke(true.B)
        d.io.RedirectPCM.poke(address.U)
        d.clock.step()
        d.io.RedirectM.poke(false.B)
        release(d)
        d.io.ahb.HTRANS.expect(0.U)
        d.io.BusStallF.expect(false.B)
        d.clock.step()
        d.io.PCD.expect(address.U)
        d.io.ValidD.expect(true.B)
        d.io.InstrAccessFaultD.expect(true.B)
        d.io.InstrD.expect(0.U)
      }
    }
  }
}
