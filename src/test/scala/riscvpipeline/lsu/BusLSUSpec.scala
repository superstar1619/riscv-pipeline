package riscvpipeline.lsu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import riscvpipeline.config.{BusConfig, CpuConfig}

private class BusLSUHarness extends Module {
  val config = CpuConfig(privilegedSupported = true,
    bus = Some(BusConfig(dtimSupported = true)))
  val io = IO(new LSUIO(config))
  val dut = Module(new BusLSU(config))
  dut.clk := clock
  dut.reset := reset.asBool
  io <> dut.io
}

class BusLSUSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  private def idle(d: BusLSUHarness): Unit = {
    d.io.MemReadE.poke(false.B)
    d.io.SuppressM.poke(false.B)
    d.io.StallM.poke(false.B)
    d.io.BusFlushM.poke(false.B)
    d.io.BigEndianM.poke(false.B)
    d.io.Funct3E.poke(2.U)
    d.io.IEUAdrE.poke(0.U)
    d.io.MemRWM.poke(0.U)
    d.io.Funct3M.poke(2.U)
    d.io.IEUAdrM.poke(0.U)
    d.io.WriteDataM.poke(0.U)
    d.io.ahb.HREADY.poke(true.B)
    d.io.ahb.HRDATA.poke(0.U)
    d.io.ahb.HRESP.poke(false.B)
  }
  private def boot(d: BusLSUHarness): Unit = {
    idle(d)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
  }

  "BusLSU" should "capture delayed reads while the whole pipeline is held" in {
    test(new BusLSUHarness) { d =>
      boot(d)
      d.io.MemRWM.poke(2.U); d.io.IEUAdrM.poke("h80000000".U)
      d.io.StallM.poke(true.B); d.io.SuppressM.poke(true.B)
      d.io.ahb.HTRANS.expect(2.U); d.io.BusStallM.expect(true.B)
      d.clock.step()
      d.io.ahb.HREADY.poke(false.B); d.io.ahb.HRDATA.poke("h12345678".U)
      d.clock.step(3)
      d.io.BusCommittedM.expect(true.B)
      d.io.ahb.HREADY.poke(true.B); d.clock.step()
      d.io.BusStallM.expect(false.B); d.io.ReadDataM.expect("h12345678".U)
      d.io.ahb.HRDATA.poke(0.U); d.clock.step(3)
      d.io.ReadDataM.expect("h12345678".U); d.io.ahb.HTRANS.expect(0.U)
    }
  }

  it should "keep accepted subword write data independent from suppression and input changes" in {
    test(new BusLSUHarness) { d =>
      boot(d)
      d.io.MemRWM.poke(1.U); d.io.Funct3M.poke(1.U)
      d.io.IEUAdrM.poke("h80000002".U); d.io.WriteDataM.poke("h1234".U)
      d.io.StallM.poke(true.B); d.io.SuppressM.poke(true.B)
      d.io.ahb.HTRANS.expect(2.U); d.clock.step()
      d.io.ahb.HREADY.poke(false.B); d.io.WriteDataM.poke(0.U)
      d.io.ahb.HWDATA.expect("h12341234".U); d.io.ahb.HWSTRB.expect(12.U)
      d.clock.step(2)
      d.io.ahb.HWDATA.expect("h12341234".U)
      d.io.ahb.HREADY.poke(true.B); d.clock.step()
      d.io.ByteMaskM.expect(0.U)
      d.io.SuppressM.poke(false.B); d.io.StallM.poke(false.B)
      d.io.ByteMaskM.expect(12.U)
    }
  }

  it should "hold a local synchronous response across an independent fetch stall" in {
    test(new BusLSUHarness) { d =>
      boot(d)
      d.io.MemRWM.poke(1.U); d.io.WriteDataM.poke("h11223344".U)
      d.clock.step(); d.io.MemRWM.poke(0.U)
      d.io.MemReadE.poke(true.B); d.clock.step()
      d.io.MemReadE.poke(false.B); d.io.MemRWM.poke(2.U)
      d.io.StallM.poke(true.B); d.io.SuppressM.poke(true.B)
      d.io.ReadDataM.expect("h11223344".U)
      d.clock.step(4); d.io.ReadDataM.expect("h11223344".U)
      d.io.BusStallM.expect(false.B); d.io.ahb.HTRANS.expect(0.U)
    }
  }

  it should "reject unmapped misaligned and read-only stores before starting a bus request" in {
    test(new BusLSUHarness) { d =>
      boot(d)
      d.io.MemRWM.poke(1.U); d.io.IEUAdrM.poke(0x1000.U)
      d.io.StoreAmoAccessFaultM.expect(true.B); d.io.ahb.HTRANS.expect(0.U)
      d.io.IEUAdrM.poke("h80000001".U)
      d.io.StoreAmoMisalignedFaultM.expect(true.B); d.io.ahb.HTRANS.expect(0.U)
      d.io.MemRWM.poke(2.U); d.io.IEUAdrM.poke("h40000000".U)
      d.io.LoadAccessFaultM.expect(true.B); d.io.ahb.HTRANS.expect(0.U)
    }
  }

  it should "format little and big endian bus read results with independent expected bytes" in {
    test(new BusLSUHarness) { d =>
      for ((funct, offset, big, expected) <- Seq(
        (2, 0, false, BigInt("11223380",16)), (2, 0, true, BigInt("80332211",16)),
        (0, 0, false, BigInt("ffffff80",16)), (4, 0, true, BigInt(0x80)),
        (5, 0, true, BigInt(0x8033)))) {
        boot(d)
        d.io.MemRWM.poke(2.U); d.io.Funct3M.poke(funct.U)
        d.io.IEUAdrM.poke((BigInt("80000000",16)+offset).U)
        d.io.BigEndianM.poke(big.B); d.clock.step()
        d.io.ahb.HRDATA.poke("h11223380".U); d.clock.step()
        d.io.ReadDataM.expect(expected.U)
      }
    }
  }
}
