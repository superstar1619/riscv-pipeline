package riscvpipeline.ebu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.ArrayBuffer

private class EBUHarnessIO extends Bundle {
  val IFUBusRW = Input(UInt(2.W))
  val LSUBusRW = Input(UInt(2.W))
  val IFUAddress = Input(UInt(34.W))
  val LSUAddress = Input(UInt(34.W))
  val WriteData = Input(UInt(32.W))
  val ByteMask = Input(UInt(4.W))
  val Stall = Input(Bool())
  val Flush = Input(Bool())
  val IFUBuffer = Output(UInt(32.W))
  val LSUBuffer = Output(UInt(32.W))
  val IFUBusy = Output(Bool())
  val LSUBusy = Output(Bool())
  val IFUReady = Output(Bool())
  val LSUReady = Output(Bool())
  val ahb = new AHBMasterIO
}

private class EBUHarness extends Module {
  val io = IO(new EBUHarnessIO)
  val ifu = Module(new AHBInterface(true))
  val lsu = Module(new AHBInterface(false))
  val ebu = Module(new EBU)
  for (m <- Seq(ifu, lsu)) {
    m.clk := clock
    m.reset := reset.asBool
    m.io.Stall := io.Stall
    m.io.Flush := io.Flush
    m.io.ByteMask := io.ByteMask
    m.io.WriteData := io.WriteData
  }
  ebu.clk := clock
  ebu.reset := reset.asBool
  ifu.io.BusRW := io.IFUBusRW
  lsu.io.BusRW := io.LSUBusRW
  ifu.io.HREADY := ebu.io.IFUHREADY
  lsu.io.HREADY := ebu.io.LSUHREADY
  ifu.io.HRDATA := ebu.io.IFUHRDATA
  lsu.io.HRDATA := ebu.io.LSUHRDATA
  ebu.io.IFUHTRANS := ifu.io.HTRANS
  ebu.io.IFUHSIZE := 2.U
  ebu.io.IFUHBURST := 0.U
  ebu.io.IFUHADDR := io.IFUAddress
  ebu.io.LSUHTRANS := lsu.io.HTRANS
  ebu.io.LSUHWRITE := lsu.io.HWRITE
  ebu.io.LSUHSIZE := 2.U
  ebu.io.LSUHBURST := 0.U
  ebu.io.LSUHADDR := io.LSUAddress
  ebu.io.LSUHWDATA := lsu.io.HWDATA
  ebu.io.LSUHWSTRB := lsu.io.HWSTRB
  ebu.io.HREADY := io.ahb.HREADY
  ebu.io.HRESP := io.ahb.HRESP
  ebu.io.HRDATA := io.ahb.HRDATA
  io.ahb.HADDR := ebu.io.HADDR
  io.ahb.HWDATA := ebu.io.HWDATA
  io.ahb.HWSTRB := ebu.io.HWSTRB
  io.ahb.HWRITE := ebu.io.HWRITE
  io.ahb.HSIZE := ebu.io.HSIZE
  io.ahb.HBURST := ebu.io.HBURST
  io.ahb.HPROT := ebu.io.HPROT
  io.ahb.HTRANS := ebu.io.HTRANS
  io.ahb.HMASTLOCK := ebu.io.HMASTLOCK
  io.IFUBuffer := ifu.io.FetchBuffer
  io.LSUBuffer := lsu.io.FetchBuffer
  io.IFUBusy := ifu.io.BusStall
  io.LSUBusy := lsu.io.BusStall
  io.IFUReady := ebu.io.IFUHREADY
  io.LSUReady := ebu.io.LSUHREADY
}

class EBUSpec extends AnyFlatSpec with riscvpipeline.ProjectTest {
  private val IAddress = BigInt("200001004", 16)
  private val DAddress = BigInt("180000008", 16)

  private def init(d: EBUHarness): Unit = {
    d.io.IFUBusRW.poke(0.U)
    d.io.LSUBusRW.poke(0.U)
    d.io.IFUAddress.poke(IAddress.U)
    d.io.LSUAddress.poke(DAddress.U)
    d.io.WriteData.poke("h12345678".U)
    d.io.ByteMask.poke(5.U)
    d.io.Stall.poke(true.B)
    d.io.Flush.poke(false.B)
    d.io.ahb.HREADY.poke(true.B)
    d.io.ahb.HRESP.poke(false.B)
    d.io.ahb.HRDATA.poke(0.U)
    d.reset.poke(true.B)
    d.clock.step()
    d.reset.poke(false.B)
  }

  behavior of "EBU"
  for (latency <- Seq(0, 1, 3, 7); write <- Seq(false, true)) {
    it should s"complete LSU then saved IFU exactly once with wait=$latency store=$write" in {
      test(new EBUHarness) { d =>
        init(d)
        val accepted = ArrayBuffer.empty[(BigInt, Boolean)]
        def step(n: Int = 1): Unit = for (_ <- 0 until n) {
          if (d.io.ahb.HREADY.peek().litToBoolean && d.io.ahb.HTRANS.peek().litValue == 2)
            accepted += ((d.io.ahb.HADDR.peek().litValue, d.io.ahb.HWRITE.peek().litToBoolean))
          d.clock.step()
        }
        d.io.IFUBusRW.poke(2.U)
        d.io.LSUBusRW.poke((if (write) 1 else 2).U)
        d.io.ahb.HADDR.expect(DAddress.U)
        d.io.ahb.HPROT.expect(3.U)
        d.io.ahb.HBURST.expect(0.U)
        d.io.ahb.HMASTLOCK.expect(false.B)
        step()
        d.io.IFUAddress.poke("h3fffffff0".U) // live address cannot replace saved fetch
        d.io.WriteData.poke("hdeadbeef".U)
        d.io.ByteMask.poke(10.U)
        d.io.Flush.poke(true.B) // accepted transactions must finish
        d.io.ahb.HREADY.poke(false.B)
        d.io.ahb.HRDATA.poke("hffffffff".U)
        if (write) {
          d.io.ahb.HWDATA.expect("h12345678".U)
          d.io.ahb.HWSTRB.expect(5.U)
        }
        step(latency)
        d.io.IFUBuffer.expect(0.U)
        d.io.LSUBuffer.expect(0.U)
        d.io.ahb.HREADY.poke(true.B)
        d.io.ahb.HRDATA.poke("h11223344".U)
        d.io.IFUReady.expect(false.B)
        d.io.LSUReady.expect(true.B)
        d.io.ahb.HADDR.expect(IAddress.U)
        d.io.ahb.HTRANS.expect(2.U)
        d.io.ahb.HPROT.expect(2.U)
        step() // LSU Data completion and saved IFU address
        d.io.LSUBuffer.expect((if (write) BigInt(0) else BigInt("11223344", 16)).U)
        d.io.IFUBuffer.expect(0.U)
        d.io.ahb.HREADY.poke(false.B)
        d.io.ahb.HRDATA.poke("hffffffff".U)
        step(latency + 1)
        d.io.IFUBuffer.expect(0.U)
        d.io.ahb.HREADY.poke(true.B)
        d.io.ahb.HRDATA.poke("h55667788".U)
        d.io.IFUReady.expect(true.B)
        step()
        d.io.IFUBuffer.expect("h55667788".U)
        d.io.IFUBusy.expect(false.B)
        d.io.LSUBusy.expect(false.B)
        d.io.ahb.HRDATA.poke(0.U)
        step(8)
        d.io.IFUBuffer.expect("h55667788".U)
        assert(accepted.toSeq == Seq((DAddress, write), (IAddress, false)))
      }
    }
  }

  it should "complete an IFU Data phase while accepting a later LSU address" in {
    test(new EBUHarness) { d =>
      init(d)
      d.io.IFUBusRW.poke(2.U)
      d.clock.step()
      d.io.ahb.HREADY.poke(false.B)
      d.io.LSUBusRW.poke(2.U)
      d.clock.step(2)
      d.io.ahb.HREADY.poke(true.B)
      d.io.ahb.HRDATA.poke("h01020304".U)
      d.io.ahb.HADDR.expect(DAddress.U)
      d.io.ahb.HTRANS.expect(2.U)
      d.clock.step()
      d.io.IFUBuffer.expect("h01020304".U)
      d.io.LSUBuffer.expect(0.U)
      d.io.ahb.HRDATA.poke("haabbccdd".U)
      d.clock.step()
      d.io.LSUBuffer.expect("haabbccdd".U)
      d.io.IFUBuffer.expect("h01020304".U)
      d.clock.step(5)
      d.io.ahb.HTRANS.expect(0.U)
    }
  }
}
