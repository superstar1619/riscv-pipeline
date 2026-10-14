package riscvpipeline

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import riscvpipeline.lsu.{DTIM, DTIMIO}
import riscvpipeline.ifu.{IFU, IFUIO, IROM, IROMIO}
import riscvpipeline.config.CpuConfig

private class SynchronousDTIMHarness extends Module {
  val io = IO(new DTIMIO(32))
  val dut = Module(new DTIM(32, 2))
  dut.clk := clock
  dut.io <> io
}

private class SynchronousIROMHarness(config: CpuConfig) extends Module {
  val io = IO(new IROMIO(config))
  val dut = Module(new IROM(config))
  dut.clk := clock
  dut.io <> io
}

private class PipelineIFUHarness(config: CpuConfig) extends Module {
  val io = IO(new IFUIO(config))
  val dut = Module(new IFU(config))
  dut.clk := clock
  dut.reset := reset.asBool
  dut.io <> io
}

class PipelineMemorySpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  behavior of "Synchronous pipeline memories"

  it should "sample DTIM read addresses at the rising edge" in {
    test(new SynchronousDTIMHarness) { dut =>
      dut.io.MemRead.poke(false.B)
      dut.io.MemWrite.poke(true.B)
      dut.io.ByteMask.poke(15.U)
      dut.io.Adr.poke(0.U)
      dut.io.WriteDataWord.poke("h11223344".U)
      dut.clock.step()
      dut.io.Adr.poke(4.U)
      dut.io.WriteDataWord.poke("h55667788".U)
      dut.clock.step()
      dut.io.MemWrite.poke(false.B)
      dut.io.MemRead.poke(true.B)
      dut.io.Adr.poke(0.U)
      dut.clock.step()
      dut.io.ReadDataWord.expect("h11223344".U)
      dut.io.Adr.poke(4.U)
      dut.io.ReadDataWord.expect("h11223344".U)
      dut.clock.step()
      dut.io.ReadDataWord.expect("h55667788".U)
    }
  }

  it should "preserve DTIM byte lanes and give writes exclusive use of the address" in {
    test(new SynchronousDTIMHarness) { dut =>
      dut.io.Adr.poke(0.U)
      dut.io.MemRead.poke(false.B)
      dut.io.MemWrite.poke(true.B)
      dut.io.ByteMask.poke(15.U)
      dut.io.WriteDataWord.poke("h11223344".U)
      dut.clock.step()
      dut.io.Adr.poke(4.U)
      dut.io.WriteDataWord.poke("h55667788".U)
      dut.clock.step()
      dut.io.Adr.poke(0.U)
      dut.io.ByteMask.poke(2.U)
      dut.io.WriteDataWord.poke("haaaaaaaa".U)
      dut.io.MemRead.poke(true.B)
      dut.clock.step()
      dut.io.ReadDataWord.expect(0.U) // Exclusive write; no simultaneous response.
      dut.io.ByteMask.poke(12.U)
      dut.io.WriteDataWord.poke("hbbbbbbbb".U)
      dut.clock.step()
      dut.io.MemWrite.poke(false.B)
      dut.clock.step()
      dut.io.ReadDataWord.expect("hbbbbaa44".U)
      dut.io.Adr.poke(12.U) // Wrap to word one.
      dut.clock.step()
      dut.io.ReadDataWord.expect("h55667788".U)
      dut.io.MemRead.poke(false.B)
      dut.clock.step()
      dut.io.ReadDataWord.expect(0.U)
    }
  }

  it should "sample IROM addresses synchronously and hold output through disabled reads" in {
    import PipelineTestSupport._
    val words = Seq(addi(1, 0, 1), addi(2, 0, 2), jal(0, 0))
    test(new SynchronousIROMHarness(image(words))) { dut =>
      dut.io.a.poke(0.U)
      dut.io.en.poke(true.B)
      dut.clock.step()
      dut.io.rd.expect(words(0).U)
      dut.io.a.poke(4.U)
      dut.io.rd.expect(words(0).U)
      dut.clock.step()
      dut.io.rd.expect(words(1).U)
      dut.io.en.poke(false.B)
      dut.io.a.poke(8.U)
      dut.clock.step(3)
      dut.io.rd.expect(words(1).U)
      dut.io.en.poke(true.B)
      dut.io.a.poke(256.U) // Capacity wrap to word zero.
      dut.clock.step()
      dut.io.rd.expect(words(0).U)
    }
  }

  it should "align synchronous instructions with PCD and redirect even when F and D stall" in {
    import PipelineTestSupport._
    val words = Seq(addi(1, 0, 1), addi(2, 0, 2), jal(0, 0))
    test(new PipelineIFUHarness(image(words, resetVector = 0x100))) { dut =>
      dut.io.PCSrcE.poke(false.B)
      dut.io.IEUAdrE.poke(0.U)
      dut.io.StallF.poke(false.B)
      dut.io.StallD.poke(false.B)
      dut.io.FlushD.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.io.PCF.expect(0x100.U)
      dut.io.ValidD.expect(false.B)
      dut.reset.poke(false.B)
      dut.clock.step()
      dut.io.PCF.expect(0x104.U)
      dut.io.PCD.expect(0x100.U)
      dut.io.PCPlus4D.expect(0x104.U)
      dut.io.InstrD.expect(words(0).U)
      dut.io.ValidD.expect(true.B)
      dut.io.StallF.poke(true.B)
      dut.io.StallD.poke(true.B)
      dut.clock.step(3)
      dut.io.PCF.expect(0x104.U)
      dut.io.PCD.expect(0x100.U)
      dut.io.InstrD.expect(words(0).U)
      dut.io.PCSrcE.poke(true.B)
      dut.io.FlushD.poke(true.B)
      dut.io.IEUAdrE.poke(0x108.U)
      dut.clock.step()
      dut.io.PCF.expect(0x108.U)
      dut.io.ValidD.expect(false.B)
      dut.io.PCSrcE.poke(false.B)
      dut.io.FlushD.poke(false.B)
      dut.io.StallF.poke(false.B)
      dut.io.StallD.poke(false.B)
      dut.clock.step()
      dut.io.PCD.expect(0x108.U)
      dut.io.PCPlus4D.expect(0x10c.U)
      dut.io.InstrD.expect(words(2).U)
      dut.io.ValidD.expect(true.B)
    }
  }

  it should "align consecutive signed and unsigned subword loads with masked stores" in {
    import PipelineTestSupport._
    val words = Seq(addi(1, 0, -128), store(1, 64), addi(2, 0, 127), store(2, 65, 0),
      BigInt(0x000081b7), addi(3, 3, 1), store(3, 66, 1),
      store(3, 67, 1), // Misaligned SH has no actual write mask.
      load(4, 0, 64, 0), load(5, 0, 64, 4), load(6, 0, 66, 1),
      load(7, 0, 66, 5), load(8, 0, 64), load(9, 0, 65, 0),
      store(4, 96), store(5, 100), store(6, 104), store(7, 108),
      store(8, 112), store(9, 116), jal(0, 0))
    test(new PipelineHarness(image(words))) { dut =>
      reset(dut)
      val writes = (0 until 48).flatMap { _ =>
        val result = if (dut.io.MemWrite.peek().litToBoolean) {
          Some((dut.io.IEUAdr.peek().litValue, dut.io.WriteData.peek().litValue,
            dut.io.ByteMask.peek().litValue))
        } else None
        dut.clock.step()
        result
      }
      assert(writes == Seq(
        (BigInt(64), BigInt("ffffff80", 16), BigInt(15)),
        (BigInt(65), BigInt(127), BigInt(2)),
        (BigInt(66), BigInt(0x8001), BigInt(12)),
        (BigInt(96), BigInt("ffffff80", 16), BigInt(15)),
        (BigInt(100), BigInt(128), BigInt(15)),
        (BigInt(104), BigInt("ffff8001", 16), BigInt(15)),
        (BigInt(108), BigInt(0x8001), BigInt(15)),
        (BigInt(112), BigInt("80017f80", 16), BigInt(15)),
        (BigInt(116), BigInt(127), BigInt(15))))
    }
  }
}
