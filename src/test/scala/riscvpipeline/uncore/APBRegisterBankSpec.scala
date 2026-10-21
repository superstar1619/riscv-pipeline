package riscvpipeline.uncore

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

private class BankHarness(latency: Int) extends Module {
  val io = IO(new APBRegisterBankIO)
  val dut = Module(new APBRegisterBank(latency))
  dut.clk := clock
  dut.reset := reset.asBool
  io <> dut.io
}
class APBRegisterBankSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  private def boot(d: BankHarness): Unit = {
    d.io.apb.PSEL.poke(false.B); d.io.apb.PENABLE.poke(false.B)
    d.io.apb.PADDR.poke(0.U); d.io.apb.PWDATA.poke(0.U)
    d.io.apb.PSTRB.poke(0.U); d.io.apb.PWRITE.poke(false.B)
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
  }
  private def setup(d: BankHarness, address: Int, write: Boolean,
    data: BigInt = 0, strobe: Int = 15): Unit = {
    d.io.apb.PSEL.poke(true.B); d.io.apb.PENABLE.poke(false.B)
    d.io.apb.PADDR.poke(address.U); d.io.apb.PWRITE.poke(write.B)
    d.io.apb.PWDATA.poke(data.U)
    d.io.apb.PSTRB.poke((if (write) strobe else 0).U)
    d.clock.step(); d.io.apb.PENABLE.poke(true.B)
  }
  private def waitReady(d: BankHarness, latency: Int): Unit = {
    for (_ <- 0 until latency) { d.io.apb.PREADY.expect(false.B); d.clock.step() }
    d.io.apb.PREADY.expect(true.B)
  }
  private def finish(d: BankHarness, latency: Int): Unit = {
    waitReady(d, latency); d.clock.step()
    d.io.apb.PSEL.poke(false.B); d.io.apb.PENABLE.poke(false.B); d.clock.step()
  }
  behavior of "APBRegisterBank"
  for (latency <- 0 to 2) {
    it should s"update scratch bytes and count completed writes once with $latency waits" in {
      test(new BankHarness(latency)) { d =>
        boot(d)
        setup(d, 0, true, BigInt("11223344", 16)); finish(d, latency)
        setup(d, 0, true, BigInt("aabbccdd", 16), 5); finish(d, latency)
        setup(d, 0, false); waitReady(d, latency)
        d.io.apb.PRDATA.expect("h11bb33dd".U); d.clock.step()
        setup(d, 4, false); waitReady(d, latency)
        d.io.apb.PRDATA.expect(2.U); d.clock.step()
        setup(d, 0, true, 0, 0); finish(d, latency)
        setup(d, 4, true, 0); finish(d, latency)
        setup(d, 8, true, 123); finish(d, latency)
        setup(d, 4, false); waitReady(d, latency)
        d.io.apb.PRDATA.expect(5.U); d.clock.step()
        setup(d, 8, false); waitReady(d, latency)
        d.io.apb.PRDATA.expect(0.U); d.clock.step()
        setup(d, 4096, false); waitReady(d, latency)
        d.io.apb.PRDATA.expect(0.U); d.clock.step()
        setup(d, 0, false); waitReady(d, latency)
        d.io.apb.PRDATA.expect("h11bb33dd".U)
      }
    }
  }
  for (latency <- 0 to 2) {
    it should s"select a register word for byte and halfword addresses with $latency waits" in {
      test(new BankHarness(latency)) { d =>
        boot(d)
        setup(d, 0, true, BigInt("11223344", 16)); finish(d, latency)
        setup(d, 1, true, BigInt("aaaaaaaa", 16), 2); finish(d, latency)
        setup(d, 0, false); waitReady(d, latency)
        d.io.apb.PRDATA.expect("h1122aa44".U); d.clock.step()
        setup(d, 2, true, BigInt("bbccbbcc", 16), 12); finish(d, latency)
        for (address <- 0 to 3) {
          setup(d, address, false); waitReady(d, latency)
          d.io.apb.PRDATA.expect("hbbccaa44".U); d.clock.step()
        }
        setup(d, 4, false); waitReady(d, latency)
        d.io.apb.PRDATA.expect(3.U); d.clock.step()
        setup(d, 5, false); waitReady(d, latency)
        d.io.apb.PRDATA.expect(3.U); d.clock.step()
        setup(d, 4097, false); waitReady(d, latency)
        d.io.apb.PRDATA.expect(0.U)
      }
    }
  }
  for (latency <- Seq(0, 1, 2, 255)) {
    it should s"count legal writes once and reset synchronously with $latency waits" in {
      test(new BankHarness(latency)) { d =>
        boot(d)
        val fullValue = BigInt("11223344", 16)
        val maskedValue = BigInt("11bb33dd", 16)

        def transfer(address: Int, write: Boolean, before: BigInt, after: BigInt,
          data: BigInt = 0, strobe: Int = 15): Unit = {
          // PSEL 可连续保持；每笔完成后都必须先回到新的 Setup。
          d.io.apb.PSEL.poke(true.B)
          d.io.apb.PENABLE.poke(false.B)
          d.io.apb.PADDR.poke(address.U)
          d.io.apb.PWRITE.poke(write.B)
          d.io.apb.PWDATA.poke(data.U)
          d.io.apb.PSTRB.poke((if (write) strobe else 0).U)
          d.io.apb.PRDATA.expect(before.U)
          d.clock.step()
          d.io.apb.PRDATA.expect(before.U) // Setup 不产生写副作用。
          d.io.apb.PENABLE.poke(true.B)
          for (_ <- 0 until latency) {
            d.io.apb.PREADY.expect(false.B)
            d.io.apb.PRDATA.expect(before.U)
            d.clock.step()
            d.io.apb.PRDATA.expect(before.U)
          }
          d.io.apb.PREADY.expect(true.B)
          d.io.apb.PRDATA.expect(before.U)
          d.clock.step() // 唯一的完成沿；下一拍立即离开 Access。
          d.io.apb.PENABLE.poke(false.B)
          d.io.apb.PRDATA.expect(after.U)
        }

        transfer(0, write = true, before = 0, after = fullValue, data = fullValue)
        transfer(0, write = true, before = fullValue, after = maskedValue,
          data = BigInt("aabbccdd", 16), strobe = 5)
        transfer(4, write = false, before = 2, after = 2)
        transfer(4, write = false, before = 2, after = 2) // 读不增加写计数。
        transfer(0, write = false, before = maskedValue, after = maskedValue)

        d.io.apb.PSEL.poke(false.B)
        d.io.apb.PENABLE.poke(false.B)
        d.io.apb.PWRITE.poke(true.B)
        d.io.apb.PWDATA.poke("hffffffff".U)
        d.io.apb.PSTRB.poke(15.U)
        for (_ <- 0 until 8) {
          d.clock.step()
          d.io.apb.PRDATA.expect(maskedValue.U)
        }
        d.io.apb.PADDR.poke(4.U)
        d.io.apb.PRDATA.expect(2.U) // Idle 的无效写载荷不得重复写或计数。

        d.reset.poke(true.B)
        d.io.apb.PRDATA.expect(2.U)
        d.io.apb.PADDR.poke(0.U)
        d.io.apb.PRDATA.expect(maskedValue.U)
        d.clock.step()
        d.io.apb.PRDATA.expect(0.U)
        d.io.apb.PADDR.poke(4.U)
        d.io.apb.PRDATA.expect(0.U)
        d.reset.poke(false.B)
      }
    }
  }
}
