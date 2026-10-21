package riscvpipeline.uncore

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import riscvpipeline.ebu.AHBInterface

private class BridgeHarness extends Module {
  val io = IO(new AHBAPBBridgeIO)
  val dut = Module(new AHBAPBBridge)
  dut.clk := clock
  dut.reset := reset.asBool
  io <> dut.io
}

private class BridgeInterfaceHarnessIO extends Bundle {
  val Address = Input(UInt(34.W))
  val BusRW = Input(UInt(2.W))
  val WriteData = Input(UInt(32.W))
  val ByteMask = Input(UInt(4.W))
  val HSEL = Output(Bool())
  val HTRANS = Output(UInt(2.W))
  val HWRITE = Output(Bool())
  val HWDATA = Output(UInt(32.W))
  val HWSTRB = Output(UInt(4.W))
  val HREADY = Output(Bool())
  val HREADYOUT = Output(Bool())
  val HRDATA = Output(UInt(32.W))
  val FetchBuffer = Output(UInt(32.W))
  val BusStall = Output(Bool())
  val BusCommitted = Output(Bool())
  val PADDR = Output(UInt(32.W))
  val PWDATA = Output(UInt(32.W))
  val PSTRB = Output(UInt(4.W))
  val PWRITE = Output(Bool())
  val PSEL = Output(Bool())
  val PENABLE = Output(Bool())
  val PREADY = Output(Bool())
  val PRDATA = Output(UInt(32.W))
}

/** 使用真实主接口与外设，验证取消桥内缓存后的载荷保持与读完成采样。 */
private class BridgeInterfaceHarness(latency: Int) extends Module {
  val io = IO(new BridgeInterfaceHarnessIO)
  val master = Module(new AHBInterface(false))
  val bridge = Module(new AHBAPBBridge)
  val bank = Module(new APBRegisterBank(latency))
  master.clk := clock
  master.reset := reset.asBool
  bridge.clk := clock
  bridge.reset := reset.asBool
  bank.clk := clock
  bank.reset := reset.asBool

  master.io.Stall := false.B
  master.io.Flush := false.B
  master.io.BusRW := io.BusRW
  master.io.WriteData := io.WriteData
  master.io.ByteMask := io.ByteMask
  master.io.HREADY := bridge.io.ahb.HREADYOUT
  master.io.HRDATA := bridge.io.ahb.HRDATA
  bridge.io.ahb.HSEL := true.B
  bridge.io.ahb.HADDR := io.Address
  bridge.io.ahb.HWDATA := master.io.HWDATA
  bridge.io.ahb.HWSTRB := master.io.HWSTRB
  bridge.io.ahb.HWRITE := master.io.HWRITE
  bridge.io.ahb.HSIZE := 2.U
  bridge.io.ahb.HTRANS := master.io.HTRANS
  bridge.io.ahb.HREADY := bridge.io.ahb.HREADYOUT
  bank.io.apb <> bridge.io.apb

  io.HSEL := bridge.io.ahb.HSEL
  io.HTRANS := master.io.HTRANS
  io.HWRITE := master.io.HWRITE
  io.HWDATA := master.io.HWDATA
  io.HWSTRB := master.io.HWSTRB
  io.HREADY := master.io.HREADY
  io.HREADYOUT := bridge.io.ahb.HREADYOUT
  io.HRDATA := bridge.io.ahb.HRDATA
  io.FetchBuffer := master.io.FetchBuffer
  io.BusStall := master.io.BusStall
  io.BusCommitted := master.io.BusCommitted
  io.PADDR := bridge.io.apb.PADDR
  io.PWDATA := bridge.io.apb.PWDATA
  io.PSTRB := bridge.io.apb.PSTRB
  io.PWRITE := bridge.io.apb.PWRITE
  io.PSEL := bridge.io.apb.PSEL
  io.PENABLE := bridge.io.apb.PENABLE
  io.PREADY := bridge.io.apb.PREADY
  io.PRDATA := bridge.io.apb.PRDATA
}

class AHBAPBBridgeSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  private def boot(d: BridgeHarness): Unit = {
    d.io.ahb.HSEL.poke(false.B); d.io.ahb.HADDR.poke(0.U)
    d.io.ahb.HWDATA.poke(0.U); d.io.ahb.HWSTRB.poke(0.U)
    d.io.ahb.HWRITE.poke(false.B); d.io.ahb.HSIZE.poke(2.U)
    d.io.ahb.HTRANS.poke(0.U); d.io.ahb.HREADY.poke(true.B)
    d.io.apb.PREADY.poke(false.B); d.io.apb.PRDATA.poke(0.U)
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    d.io.apb.PSEL.expect(false.B); d.io.ahb.HREADYOUT.expect(true.B)
  }
  behavior of "AHBAPBBridge"
  it should "preserve the accepted address and legal AHB data through APB waits" in {
    test(new BridgeHarness) { d =>
      boot(d)
      d.io.ahb.HSEL.poke(true.B); d.io.ahb.HTRANS.poke(2.U)
      d.io.ahb.HADDR.poke("h10000004".U); d.io.ahb.HWRITE.poke(true.B)
      d.io.ahb.HWDATA.poke("hbad00000".U); d.clock.step()
      d.io.ahb.HSEL.poke(false.B); d.io.ahb.HTRANS.poke(0.U)
      d.io.ahb.HREADY.poke(false.B)
      d.io.ahb.HWDATA.poke("h12345678".U); d.io.ahb.HWSTRB.poke(5.U)
      d.io.apb.PSEL.expect(true.B); d.io.apb.PENABLE.expect(false.B)
      d.io.apb.PWDATA.expect("h12345678".U); d.io.ahb.HREADYOUT.expect(false.B)
      d.clock.step()
      for (_ <- 0 until 4) {
        // 无新地址请求时可改变地址控制；数据相位载荷必须保持到完成沿。
        d.io.ahb.HADDR.poke(0.U); d.io.ahb.HWRITE.poke(false.B)
        d.io.apb.PSEL.expect(true.B); d.io.apb.PENABLE.expect(true.B)
        d.io.apb.PADDR.expect("h10000004".U); d.io.apb.PWRITE.expect(true.B)
        d.io.apb.PWDATA.expect("h12345678".U); d.io.apb.PSTRB.expect(5.U)
        d.io.ahb.HREADYOUT.expect(false.B); d.clock.step()
      }
      d.io.apb.PREADY.poke(true.B); d.io.apb.PRDATA.poke("hcafebabe".U)
      d.io.ahb.HREADY.poke(true.B); d.io.ahb.HREADYOUT.expect(true.B)
      d.io.ahb.HRDATA.expect("hcafebabe".U); d.clock.step()
      d.io.apb.PSEL.expect(false.B); d.io.apb.PENABLE.expect(false.B)
      d.io.apb.PRDATA.poke("hffffffff".U); d.io.ahb.HRDATA.expect(0.U)
    }
  }
  it should "accept the next address at completion without skipping its Setup phase" in {
    test(new BridgeHarness) { d =>
      boot(d)
      d.io.ahb.HSEL.poke(true.B); d.io.ahb.HTRANS.poke(2.U)
      d.io.ahb.HADDR.poke(16.U); d.io.ahb.HWRITE.poke(true.B); d.clock.step()
      d.io.ahb.HREADY.poke(false.B)
      d.io.ahb.HWDATA.poke("h11223344".U); d.io.ahb.HWSTRB.poke(15.U)
      d.io.apb.PENABLE.expect(false.B); d.io.ahb.HREADYOUT.expect(false.B)
      d.clock.step()
      d.io.apb.PENABLE.expect(true.B); d.io.apb.PADDR.expect(16.U)
      d.io.apb.PWDATA.expect("h11223344".U); d.io.apb.PSTRB.expect(15.U)
      d.io.apb.PREADY.poke(true.B)
      d.io.ahb.HREADY.poke(true.B); d.io.ahb.HADDR.poke(32.U)
      // 该沿仍完成第一笔 Data，第二笔地址不能提前替换写载荷。
      d.io.apb.PWDATA.expect("h11223344".U); d.clock.step()
      d.io.apb.PADDR.expect(32.U); d.io.apb.PSEL.expect(true.B)
      d.io.apb.PENABLE.expect(false.B); d.io.ahb.HREADYOUT.expect(false.B)
      d.io.ahb.HSEL.poke(false.B); d.io.ahb.HTRANS.poke(0.U)
      d.io.ahb.HREADY.poke(false.B)
      d.io.ahb.HWDATA.poke("haabbccdd".U); d.io.ahb.HWSTRB.poke(5.U)
      d.io.apb.PWDATA.expect("haabbccdd".U); d.io.apb.PSTRB.expect(5.U)
      d.clock.step()
      d.io.apb.PENABLE.expect(true.B); d.io.apb.PADDR.expect(32.U)
      d.io.apb.PWRITE.expect(true.B); d.io.apb.PWDATA.expect("haabbccdd".U)
      d.io.apb.PSTRB.expect(5.U); d.io.ahb.HREADYOUT.expect(true.B)
      d.io.ahb.HREADY.poke(true.B); d.clock.step()
      d.io.apb.PSEL.expect(false.B)
    }
  }
  it should "return the current APB read value at completion and zero when unselected" in {
    test(new BridgeHarness) { d =>
      boot(d)
      d.io.ahb.HSEL.poke(true.B); d.io.ahb.HTRANS.poke(2.U)
      d.io.ahb.HADDR.poke(16.U); d.clock.step()
      d.io.ahb.HSEL.poke(false.B); d.io.ahb.HTRANS.poke(0.U)
      d.io.ahb.HREADY.poke(false.B); d.clock.step()
      d.io.apb.PENABLE.expect(true.B); d.io.apb.PWRITE.expect(false.B)
      d.io.apb.PRDATA.poke(55.U); d.io.ahb.HRDATA.expect(55.U)
      d.io.ahb.HREADYOUT.expect(false.B); d.clock.step(2)
      d.io.apb.PRDATA.poke(66.U); d.io.ahb.HRDATA.expect(66.U)
      d.io.apb.PREADY.poke(true.B); d.io.ahb.HREADY.poke(true.B)
      d.io.ahb.HREADYOUT.expect(true.B); d.clock.step()
      d.io.apb.PSEL.expect(false.B); d.io.apb.PRDATA.poke(77.U)
      d.io.ahb.HRDATA.expect(0.U)
    }
  }
  it should "ignore unaccepted requests and synchronously cancel a pending APB transfer" in {
    test(new BridgeHarness) { d =>
      boot(d)
      d.io.ahb.HSEL.poke(true.B); d.io.ahb.HTRANS.poke(2.U)
      d.io.ahb.HREADY.poke(false.B); d.clock.step(3)
      d.io.apb.PSEL.expect(false.B)
      d.io.ahb.HREADY.poke(true.B); d.clock.step()
      d.io.apb.PSEL.expect(true.B)
      d.reset.poke(true.B); d.io.apb.PSEL.expect(true.B)
      d.clock.step(); d.io.apb.PSEL.expect(false.B)
      d.io.ahb.HRESP.expect(false.B)
    }
  }

  for (latency <- Seq(0, 1, 4)) {
    it should s"hold master payloads and capture completed reads with $latency waits" in {
      test(new BridgeInterfaceHarness(latency)) { d =>
        d.io.Address.poke(0.U)
        d.io.BusRW.poke(0.U)
        d.io.WriteData.poke(0.U)
        d.io.ByteMask.poke(0.U)
        d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        d.io.PSEL.expect(false.B)
        d.io.HREADY.expect(true.B)
        d.io.FetchBuffer.expect(0.U)
        var expectedFetch = BigInt(0)

        def transfer(address: Int, write: Boolean, data: BigInt = 0,
          mask: Int = 15, readValue: BigInt = 0): Unit = {
          d.io.Address.poke(address.U)
          d.io.BusRW.poke((if (write) 1 else 2).U)
          d.io.WriteData.poke(data.U)
          d.io.ByteMask.poke(mask.U)
          d.io.HSEL.expect(true.B)
          d.io.HTRANS.expect(2.U)
          d.io.HWRITE.expect(write.B)
          d.io.HREADY.expect(true.B)
          d.io.HREADYOUT.expect(true.B)
          d.io.PSEL.expect(false.B)
          d.clock.step() // 地址接受；真正的 AHBInterface 在此保存写载荷。

          d.io.BusRW.poke(0.U)
          d.io.Address.poke(4096.U)
          d.io.WriteData.poke("hdeadbeef".U)
          d.io.ByteMask.poke(10.U)

          def expectDataPhase(enable: Boolean): Unit = {
            d.io.HSEL.expect(true.B)
            d.io.HTRANS.expect(0.U)
            d.io.HWRITE.expect(write.B)
            d.io.PSEL.expect(true.B)
            d.io.PENABLE.expect(enable.B)
            d.io.PADDR.expect(address.U)
            d.io.PWRITE.expect(write.B)
            d.io.BusStall.expect(true.B)
            d.io.BusCommitted.expect(true.B)
            d.io.FetchBuffer.expect(expectedFetch.U)
            if (write) {
              d.io.HWDATA.expect(data.U)
              d.io.HWSTRB.expect(mask.U)
              d.io.PWDATA.expect(data.U)
              d.io.PSTRB.expect(mask.U)
            }
          }

          var waitCycles = 0
          while (!d.io.HREADY.peek().litToBoolean) {
            expectDataPhase(enable = waitCycles != 0)
            d.io.HREADYOUT.expect(false.B)
            if (waitCycles != 0) { d.io.PREADY.expect(false.B) }
            d.clock.step()
            waitCycles += 1
            assert(waitCycles <= latency + 1, "AHB completion exceeded the APB wait bound")
          }
          assert(waitCycles == latency + 1, "AHB wait must include one full APB Setup")
          expectDataPhase(enable = true)
          d.io.PREADY.expect(true.B)
          d.io.HREADYOUT.expect(true.B)
          if (!write) { d.io.HRDATA.expect(readValue.U) }
          d.clock.step() // 只有该 Data 完成沿可以更新 FetchBuffer。
          if (!write) { expectedFetch = readValue }
          d.io.FetchBuffer.expect(expectedFetch.U)
          d.io.PSEL.expect(false.B)
          d.io.PENABLE.expect(false.B)
          d.io.HRDATA.expect(0.U)
          d.io.BusCommitted.expect(true.B)
          d.clock.step() // 消费 Mem3，允许下一地址。
          d.io.BusCommitted.expect(false.B)
          d.io.FetchBuffer.expect(expectedFetch.U)
        }

        transfer(0, write = true, data = BigInt("11223344", 16))
        transfer(4, write = false, readValue = 1)
        transfer(0, write = true, data = BigInt("aabbccdd", 16), mask = 5)
        transfer(4, write = false, readValue = 2)
        transfer(0, write = false, readValue = BigInt("11bb33dd", 16))
        for (_ <- 0 until 8) {
          d.io.PSEL.expect(false.B)
          d.io.FetchBuffer.expect("h11bb33dd".U)
          d.clock.step()
        }
        transfer(4, write = false, readValue = 2)
        transfer(0, write = false, readValue = BigInt("11bb33dd", 16))
        transfer(0, write = true, data = BigInt("55667788", 16))
        // 通过合法写事务改变真实外设数据，旧读结果仍须保留到下一次读完成。
        for (_ <- 0 until 8) {
          d.io.PSEL.expect(false.B)
          d.io.PRDATA.expect("h55667788".U)
          d.io.FetchBuffer.expect("h11bb33dd".U)
          d.clock.step()
        }
        transfer(0, write = false, readValue = BigInt("55667788", 16))
      }
    }
  }
}
