package riscvpipeline.privileged

import chisel3._
import chisel3.stage.ChiselStage
import chisel3.util.MuxLookup
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.Files
import scala.collection.mutable
import scala.language.reflectiveCalls
import scala.util.Random

private class CSRStatusHarness(s: Boolean = true, vm: Boolean = false, be: Boolean = false)
    extends Module {
  val io = IO(new CSRStatusIO)
  val dut = Module(new CSRStatus(s, vm, be))
  dut.clk := clock
  dut.reset := reset.asBool
  io <> dut.io
}

// 三轮生产模块仅在测试层连接；写使能来自真实CSRAccess，返回来自PrivDecode。
private class StatusAccessHarness extends Module {
  val io = IO(new Bundle {
    val Instr = Input(UInt(32.W))
    val Src = Input(UInt(32.W))
    val Mode = Input(UInt(2.W))
    val Valid = Input(Bool())
    val Stall = Input(Bool())
    val Flush = Input(Bool())
    val M = Output(UInt(32.W))
    val S = Output(UInt(32.W))
    val H = Output(UInt(32.W))
    val Result = Output(UInt(32.W))
    val Illegal = Output(Bool())
    val Ret = Output(Bool())
  })
  val status = Module(new CSRStatus)
  val access = Module(new CSRAccess)
  val decode = Module(new PrivDecode)
  status.clk := clock
  status.reset := reset.asBool
  val address = access.io.CSRAdrM
  val old = MuxLookup(address, 0.U(32.W))(Seq(
    "h300".U -> status.io.MSTATUS_REGW, "h310".U -> status.io.MSTATUSH_REGW,
    "h100".U -> status.io.SSTATUS_REGW))
  access.io.InstrM := io.Instr
  access.io.ValidM := io.Valid
  access.io.PrivilegeModeW := io.Mode
  access.io.SrcAM := io.Src
  access.io.CSRReadValM := old
  access.io.CSRModifyValM := old
  access.io.CSRExistsM := address === "h300".U || address === "h310".U ||
    address === "h100".U
  access.io.CSRAccessAllowedM := true.B
  decode.io.InstrM := io.Instr
  decode.io.ValidM := io.Valid
  decode.io.PrivilegeModeW := io.Mode
  decode.io.STATUS_TSR := status.io.STATUS_TSR
  decode.io.STATUS_TVM := status.io.STATUS_TVM
  decode.io.STATUS_TW := status.io.STATUS_TW
  decode.io.WFITimeoutM := false.B
  decode.io.IllegalIEUInstrM := false.B
  decode.io.IllegalCSRAccessM := access.io.IllegalCSRAccessM
  status.io.ValidM := io.Valid
  status.io.StallW := io.Stall
  status.io.FlushM := io.Flush
  status.io.WriteMSTATUSM := access.io.CSRWriteM && address === "h300".U
  status.io.WriteMSTATUSHM := access.io.CSRWriteM && address === "h310".U
  status.io.WriteSSTATUSM := access.io.CSRWriteM && address === "h100".U
  status.io.CSRWriteValM := access.io.CSRWriteValM
  status.io.TrapM := false.B
  status.io.PrivilegeModeW := io.Mode
  status.io.NextPrivilegeModeM := 3.U(2.W)
  status.io.mretM := decode.io.mretM
  status.io.sretM := decode.io.sretM
  status.io.SelHPTW := false.B
  io.M := status.io.MSTATUS_REGW
  io.S := status.io.SSTATUS_REGW
  io.H := status.io.MSTATUSH_REGW
  io.Result := access.io.CSRResultM
  io.Illegal := decode.io.IllegalInstrFaultM
  io.Ret := decode.io.RetM
}

class CSRStatusSpec extends AnyFlatSpec with ChiselScalatestTester {
  private def hex(s: String): BigInt = BigInt(s, 16)
  private case class Inputs(
    word: BigInt = 0, mwrite: Boolean = false, hwrite: Boolean = false,
    swrite: Boolean = false, trap: Boolean = false, mode: Int = 3, next: Int = 3,
    mret: Boolean = false, sret: Boolean = false, valid: Boolean = true,
    stall: Boolean = false, flush: Boolean = false, ptw: Boolean = false
  )
  private def drive(d: CSRStatusHarness, a: Inputs): Unit = {
    d.io.CSRWriteValM.poke(a.word.U)
    d.io.WriteMSTATUSM.poke(a.mwrite.B)
    d.io.WriteMSTATUSHM.poke(a.hwrite.B)
    d.io.WriteSSTATUSM.poke(a.swrite.B)
    d.io.TrapM.poke(a.trap.B)
    d.io.PrivilegeModeW.poke(a.mode.U)
    d.io.NextPrivilegeModeM.poke(a.next.U)
    d.io.mretM.poke(a.mret.B)
    d.io.sretM.poke(a.sret.B)
    d.io.ValidM.poke(a.valid.B)
    d.io.StallW.poke(a.stall.B)
    d.io.FlushM.poke(a.flush.B)
    d.io.SelHPTW.poke(a.ptw.B)
  }
  private def boot(d: CSRStatusHarness): Unit = {
    drive(d, Inputs(valid = false))
    d.reset.poke(true.B)
    d.clock.step()
    d.reset.poke(false.B)
    expect(d, 0, 0, 0)
  }
  private def cycle(d: CSRStatusHarness, a: Inputs): Unit = {
    drive(d, a)
    d.clock.step()
  }
  private def expect(d: CSRStatusHarness, m: BigInt, s: BigInt, h: BigInt,
                     endian: Boolean = false): Unit = {
    d.io.MSTATUS_REGW.expect(m.U)
    d.io.SSTATUS_REGW.expect(s.U)
    d.io.MSTATUSH_REGW.expect(h.U)
    d.io.STATUS_MPP.expect(((m >> 11) & 3).U)
    d.io.STATUS_FS.expect(0.U)
    for ((signal, bit) <- Seq(d.io.STATUS_SPP -> 8, d.io.STATUS_TSR -> 22,
         d.io.STATUS_TW -> 21, d.io.STATUS_TVM -> 20, d.io.STATUS_MIE -> 3,
         d.io.STATUS_SIE -> 1, d.io.STATUS_MXR -> 19, d.io.STATUS_SUM -> 18,
         d.io.STATUS_MPRV -> 17)) signal.expect(m.testBit(bit).B)
    d.io.BigEndianM.expect(endian.B)
  }

  // 独立按字段的软件状态机：避免复用RTL整字掩码和更新表达式。
  private class Model(s: Boolean, vm: Boolean, be: Boolean) {
    private val positions = Map("sie" -> 1, "mie" -> 3, "spie" -> 5, "ube" -> 6,
      "mpie" -> 7, "spp" -> 8, "mpp" -> 11, "mprv" -> 17, "sum" -> 18,
      "mxr" -> 19, "tvm" -> 20, "tw" -> 21, "tsr" -> 22)
    private val sFields = Set("sie", "spie", "spp", "ube", "sum", "mxr")
    private val supported = Set(0, 3) ++ (if (s) Set(1) else Set.empty[Int])
    private val writable = Set("mie", "mpie", "mpp", "mprv", "tw") ++
      (if (s) Set("sie", "spie", "spp", "mxr", "tvm", "tsr") else Set.empty) ++
      (if (vm) Set("sum") else Set.empty) ++ (if (be) Set("ube") else Set.empty)
    private val values = mutable.Map.from((positions.keys ++ Seq("mbe", "sbe"))
      .map(_ -> 0))
    private def field(word: BigInt, name: String): Int =
      ((word / (BigInt(1) << positions(name))) % (if (name == "mpp") 4 else 2)).toInt
    private def pack(names: Set[String]): BigInt = names.toSeq.map { name =>
      BigInt(values(name)) << positions(name)
    }.sum
    def m: BigInt = pack(positions.keySet)
    def supervisor: BigInt = if (s) pack(sFields) else BigInt(0)
    def high: BigInt = BigInt(values("mbe") * 32 + values("sbe") * 16)
    def endian(a: Inputs): Boolean = {
      val mode = if (vm && a.ptw) 1
        else if (a.mode == 3 && values("mprv") == 1) values("mpp") else a.mode
      be && Map(0 -> "ube", 1 -> "sbe", 3 -> "mbe").get(mode)
        .exists(name => values(name) == 1)
    }
    def update(a: Inputs, resetting: Boolean = false): Unit = {
      if (resetting) values.keys.toSeq.foreach(values(_) = 0)
      else if (a.valid && !a.stall && !a.flush) {
        if (a.trap) {
          if (supported(a.mode) && (a.next == 3 || (s && a.next == 1)) &&
              a.next >= a.mode) {
            if (a.next == 3) {
              values("mpie") = values("mie"); values("mie") = 0
              values("mpp") = a.mode
            } else {
              values("spie") = values("sie"); values("sie") = 0
              values("spp") = if (a.mode == 1) 1 else 0
            }
          }
        } else if (a.mret) {
          if (a.mode == 3) {
            if (values("mpp") != 3) values("mprv") = 0
            values("mie") = values("mpie"); values("mpie") = 1; values("mpp") = 0
          }
        } else if (a.sret) {
          if (s && (a.mode == 3 || (a.mode == 1 && values("tsr") == 0))) {
            values("sie") = values("spie"); values("spie") = 1
            values("spp") = 0; values("mprv") = 0
          }
        } else if (a.mwrite) {
          for (name <- writable) {
            val value = field(a.word, name)
            if (name != "mpp" || supported(value)) values(name) = value
          }
        } else if (a.hwrite) {
          values("mbe") = if (be && a.word.testBit(5)) 1 else 0
          values("sbe") = if (s && be && a.word.testBit(4)) 1 else 0
        } else if (a.swrite && s) {
          for (name <- sFields.intersect(writable)) values(name) = field(a.word, name)
        }
      }
    }
  }

  behavior of "CSRStatus"

  it should "reset synchronously and override stalls flushes and all simultaneous updates" in {
    test(new CSRStatusHarness(true, true, true)) { d =>
      boot(d)
      cycle(d, Inputs(hex("ffffffff"), mwrite = true))
      cycle(d, Inputs(hex("ffffffff"), hwrite = true))
      drive(d, Inputs(mwrite = true, hwrite = true, swrite = true, trap = true,
        mret = true, sret = true, valid = false, stall = true, flush = true))
      d.reset.poke(true.B)
      expect(d, hex("7e19ea"), hex("c0162"), 0x30, endian = true) // 边沿前不复位。
      d.clock.step()
      expect(d, 0, 0, 0)
      d.reset.poke(false.B)
    }
  }

  it should "apply fixed literal writable masks in all six supported configurations" in {
    val rows = Seq(
      (false, false, false, "221888", "0", 0),
      (false, false, true, "2218c8", "0", 0x20),
      (true, false, false, "7a19aa", "80122", 0),
      (true, false, true, "7a19ea", "80162", 0x30),
      (true, true, false, "7e19aa", "c0122", 0),
      (true, true, true, "7e19ea", "c0162", 0x30))
    for ((s, vm, be, m, view, h) <- rows) {
      test(new CSRStatusHarness(s, vm, be)) { d =>
        boot(d)
        cycle(d, Inputs(hex("ffffffff"), mwrite = true))
        cycle(d, Inputs(hex("ffffffff"), hwrite = true))
        expect(d, hex(m), hex(view), h, endian = be)
        cycle(d, Inputs(mwrite = true))
        cycle(d, Inputs(hwrite = true))
        expect(d, 0, 0, 0)
      }
    }
  }

  it should "share sstatus fields while protecting machine fields and the high half" in {
    test(new CSRStatusHarness(true, true, true)) { d =>
      boot(d)
      cycle(d, Inputs(hex("ffffffff"), mwrite = true))
      cycle(d, Inputs(0x30, hwrite = true))
      cycle(d, Inputs(swrite = true))
      expect(d, hex("721888"), 0, 0x30, endian = true)
      cycle(d, Inputs(hex("ffffffff"), swrite = true))
      expect(d, hex("7e19ea"), hex("c0162"), 0x30, endian = true)
      cycle(d, Inputs(mwrite = true))
      expect(d, 0, 0, 0x30, endian = true) // RV32 mstatus写不修改mstatush。
    }
  }

  it should "retain old MPP on reserved or unsupported software encodings" in {
    for (s <- Seq(false, true)) {
      test(new CSRStatusHarness(s)) { d =>
        boot(d)
        cycle(d, Inputs(0x1800, mwrite = true))
        cycle(d, Inputs(0x1008, mwrite = true)) // MPP=10非法，其他字段仍可写。
        expect(d, 0x1808, 0, 0)
        cycle(d, Inputs(0x808, mwrite = true))
        expect(d, if (s) BigInt(0x808) else BigInt(0x1808), 0, 0)
        cycle(d, Inputs(8, mwrite = true))
        expect(d, 8, 0, 0)
        cycle(d, Inputs(0x1000, mwrite = true))
        expect(d, 0, 0, 0)
      }
    }
  }

  it should "save machine trap state and preserve the supervisor stack" in {
    test(new CSRStatusHarness) { d =>
      for ((mode, m) <- Seq(0 -> 0x20082, 1 -> 0x20882, 3 -> 0x21882)) {
        boot(d)
        cycle(d, Inputs(0x2180a, mwrite = true))
        cycle(d, Inputs(trap = true, mode = mode, next = 3))
        expect(d, m, 2, 0)
      }
    }
  }

  it should "save supervisor trap state without modifying the machine stack" in {
    test(new CSRStatusHarness) { d =>
      for ((mode, m, s) <- Seq((0, 0x21828, 0x20), (1, 0x21928, 0x120))) {
        boot(d)
        cycle(d, Inputs(0x2180a, mwrite = true))
        cycle(d, Inputs(trap = true, mode = mode, next = 1))
        expect(d, m, s, 0)
      }
    }
  }

  it should "restore MIE from old MPIE and clear MPRV only when MRET leaves machine mode" in {
    test(new CSRStatusHarness) { d =>
      for (target <- Seq(0, 1, 3); mpie <- Seq(0, 1)) {
        boot(d)
        val before = BigInt(0x2000a + (target << 11) + (mpie << 7))
        cycle(d, Inputs(before, mwrite = true))
        cycle(d, Inputs(mret = true))
        val after = BigInt(0x82 + (mpie << 3) + (if (target == 3) 0x20000 else 0))
        expect(d, after, 2, 0)
      }
    }
  }

  it should "restore SIE from old SPIE and clear SPP and MPRV on SRET" in {
    test(new CSRStatusHarness) { d =>
      for (spie <- Seq(0, 1); mode <- Seq(1, 3)) {
        boot(d)
        cycle(d, Inputs(0x2190a + (spie << 5), mwrite = true))
        cycle(d, Inputs(sret = true, mode = mode))
        expect(d, 0x1828 + (spie << 1), 0x20 + (spie << 1), 0)
      }
    }
  }

  it should "prioritize traps and returns over software and order the three writes" in {
    test(new CSRStatusHarness(true, true, true)) { d =>
      boot(d)
      val all = Inputs(hex("ffffffff"), mwrite = true, hwrite = true, swrite = true,
        mret = true, sret = true)
      cycle(d, all.copy(trap = true))
      expect(d, 0x1800, 0, 0)
      cycle(d, all)
      expect(d, 0x80, 0, 0)
      cycle(d, all.copy(mret = false))
      expect(d, 0xa0, 0x20, 0)
      cycle(d, all.copy(mret = false, sret = false))
      expect(d, hex("7e19ea"), hex("c0162"), 0)
      boot(d)
      cycle(d, Inputs(hex("ffffffff"), hwrite = true, swrite = true))
      expect(d, 0, 0, 0x30, endian = true)
    }
  }

  it should "hold every state bit for invalid stalled or flushed updates" in {
    test(new CSRStatusHarness(true, true, true)) { d =>
      boot(d)
      cycle(d, Inputs(0x2180a, mwrite = true))
      cycle(d, Inputs(0x30, hwrite = true))
      for (valid <- Seq(false, true); stall <- Seq(false, true); flush <- Seq(false, true)
           if !valid || stall || flush) {
        cycle(d, Inputs(hex("ffffffff"), mwrite = true, hwrite = true, swrite = true,
          trap = true, mode = 0, next = 1, mret = true, sret = true,
          valid = valid, stall = stall, flush = flush))
        expect(d, 0x2180a, 2, 0x30)
      }
      cycle(d, Inputs(trap = true, mode = 0, next = 1))
      expect(d, 0x21828, 0x20, 0x30)
    }
  }

  it should "hold on unsupported trap or return requests without applying lower writes" in {
    for (s <- Seq(false, true)) {
      test(new CSRStatusHarness(s)) { d =>
        boot(d)
        cycle(d, Inputs(0x88, mwrite = true))
        val events = Seq(Inputs(trap = true, mode = 2), Inputs(trap = true, next = 0),
          Inputs(trap = true, next = 2), Inputs(trap = true, mode = 3, next = 1),
          Inputs(mret = true, mode = 0), Inputs(mret = true, mode = 1),
          Inputs(sret = true, mode = 0), Inputs(sret = true, mode = 2)) ++
          (if (!s) Seq(Inputs(trap = true, mode = 0, next = 1),
            Inputs(sret = true), Inputs(trap = true, mode = 1)) else Seq.empty)
        for (event <- events) {
          cycle(d, event.copy(mwrite = true, word = hex("ffffffff")))
          expect(d, 0x88, 0, 0)
        }
        if (s) {
          cycle(d, Inputs(0x400088, mwrite = true))
          cycle(d, Inputs(sret = true, mode = 1, mwrite = true))
          expect(d, 0x400088, 0, 0)
          cycle(d, Inputs(sret = true, mode = 3))
          expect(d, 0x4000a8, 0x20, 0)
        }
      }
    }
  }

  it should "select endian state combinationally for mode MPRV and page table walks" in {
    test(new CSRStatusHarness(true, true, true)) { d =>
      boot(d)
      for (ube <- 0 to 1; sbe <- 0 to 1; mbe <- 0 to 1;
           mprv <- 0 to 1; mpp <- Seq(0, 1, 3)) {
        cycle(d, Inputs((ube << 6) + (mprv << 17) + (mpp << 11), mwrite = true))
        cycle(d, Inputs((mbe << 5) + (sbe << 4), hwrite = true))
        for (mode <- 0 until 4; ptw <- Seq(false, true)) {
          drive(d, Inputs(mode = mode, ptw = ptw, valid = false))
          val effective = if (ptw) 1 else if (mode == 3 && mprv == 1) mpp else mode
          val selected = Map(0 -> ube, 1 -> sbe, 2 -> 0, 3 -> mbe)(effective) == 1
          d.io.BigEndianM.expect(selected.B)
        }
      }
    }
    // 没有VM时SelHPTW输入不改变数据访问模式。
    test(new CSRStatusHarness(true, false, true)) { d =>
      boot(d)
      cycle(d, Inputs(0x10, hwrite = true))
      drive(d, Inputs(ptw = true, valid = false))
      d.io.BigEndianM.expect(false.B)
    }
  }

  it should "exercise CSR read modify write and return authorization with prior modules" in {
    test(new StatusAccessHarness) { d =>
      def instruction(word: BigInt, src: BigInt = 0, mode: Int = 3): Unit = {
        d.io.Instr.poke(word.U); d.io.Src.poke(src.U); d.io.Mode.poke(mode.U)
        d.io.Valid.poke(true.B); d.io.Stall.poke(false.B); d.io.Flush.poke(false.B)
      }
      def csr(adr: Int, f: Int, src: BigInt, field: Int = 1, mode: Int = 3): Unit =
        instruction((BigInt(adr) << 20) | (BigInt(field) << 15) |
          (BigInt(f) << 12) | 0xf3, src, mode)
      instruction(0x13)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      csr(0x300, 5, 0, field = 8)
      d.io.Result.expect(0.U); d.io.M.expect(0.U); d.clock.step(); d.io.M.expect(8.U)
      csr(0x300, 2, 0x80)
      d.io.Result.expect(8.U); d.clock.step(); d.io.M.expect(0x88.U)
      csr(0x100, 1, 2, mode = 1)
      d.clock.step(); d.io.M.expect(0x8a.U); d.io.S.expect(2.U)
      csr(0x300, 3, 8)
      d.io.Result.expect(0x8a.U); d.clock.step(); d.io.M.expect(0x82.U)
      for ((adr, mode) <- Seq(0x300 -> 0, 0x100 -> 0, 0x301 -> 3)) {
        csr(adr, 1, hex("ffffffff"), mode = mode)
        d.io.Illegal.expect(true.B); d.clock.step(); d.io.M.expect(0x82.U)
      }
      csr(0x300, 1, 0x400002)
      d.clock.step(); d.io.M.expect(0x400002.U)
      instruction(hex("10200073"), mode = 1)
      d.io.Illegal.expect(true.B); d.io.Ret.expect(false.B)
      d.clock.step(); d.io.M.expect(0x400002.U)
      instruction(hex("10200073"))
      d.io.Illegal.expect(false.B); d.io.Ret.expect(true.B)
      d.clock.step(); d.io.M.expect(0x400020.U)
      csr(0x300, 1, 8)
      d.io.Flush.poke(true.B); d.clock.step(); d.io.M.expect(0x400020.U)
      d.io.Flush.poke(false.B); d.io.Stall.poke(true.B)
      d.clock.step(); d.io.M.expect(0x400020.U)
    }
  }

  it should "match a field based seeded sequential reference across all configurations" in {
    for ((s, vm, be) <- Seq((false, false, false), (false, false, true),
         (true, false, false), (true, false, true), (true, true, false), (true, true, true))) {
      test(new CSRStatusHarness(s, vm, be)) { d =>
        boot(d)
        val model = new Model(s, vm, be)
        val random = new Random(0x85a72026L)
        for (i <- 0 until 512) {
          val a = Inputs(BigInt(32, random), mwrite = random.nextBoolean(),
            hwrite = i % 3 == 0, swrite = random.nextBoolean(), trap = i % 7 == 0,
            mode = random.nextInt(4), next = random.nextInt(4), mret = i % 5 == 0,
            sret = i % 11 == 0, valid = i % 13 != 0, stall = i % 17 == 0,
            flush = i % 19 == 0, ptw = random.nextBoolean())
          val resetting = i % 67 == 0
          drive(d, a); d.reset.poke(resetting.B)
          expect(d, model.m, model.supervisor, model.high, model.endian(a))
          d.clock.step(); model.update(a, resetting)
          expect(d, model.m, model.supervisor, model.high, model.endian(a))
        }
      }
    }
  }

  it should "emit explicit clock reset and stable RV32 ports for all configurations" in {
    val inputs = Map("ValidM" -> 1, "StallW" -> 1, "FlushM" -> 1,
      "WriteMSTATUSM" -> 1, "WriteMSTATUSHM" -> 1, "WriteSSTATUSM" -> 1,
      "TrapM" -> 1, "PrivilegeModeW" -> 2, "NextPrivilegeModeM" -> 2,
      "mretM" -> 1, "sretM" -> 1, "CSRWriteValM" -> 32, "SelHPTW" -> 1)
    val outputs = Map("MSTATUS_REGW" -> 32, "SSTATUS_REGW" -> 32,
      "MSTATUSH_REGW" -> 32, "STATUS_MPP" -> 2, "STATUS_FS" -> 2,
      "STATUS_SPP" -> 1, "STATUS_TSR" -> 1, "STATUS_TW" -> 1, "STATUS_TVM" -> 1,
      "STATUS_MIE" -> 1, "STATUS_SIE" -> 1, "STATUS_MXR" -> 1, "STATUS_SUM" -> 1,
      "STATUS_MPRV" -> 1, "BigEndianM" -> 1)
    for ((s, vm, be) <- Seq((false, false, false), (false, false, true),
         (true, false, false), (true, false, true), (true, true, false), (true, true, true))) {
      val dir = Files.createTempDirectory("csrstatus-interface-")
      val rtl = (new ChiselStage).emitVerilog(new CSRStatus(s, vm, be),
        Array("--target-dir", dir.toString))
      val pattern = "(?m)^\\s*(input|output)\\s+(?:\\[(\\d+):0\\]\\s+)?(\\w+)".r
      val ports = pattern.findAllMatchIn(rtl).map { p =>
        p.group(3) -> (p.group(1), Option(p.group(2)).map(_.toInt + 1).getOrElse(1))
      }.toMap
      val wanted = inputs.map { case (n, w) => s"io_$n" -> ("input", w) } ++
        outputs.map { case (n, w) => s"io_$n" -> ("output", w) } ++
        Map("clk" -> ("input", 1), "reset" -> ("input", 1))
      assert(ports == wanted)
      assert(rtl.contains("always @(posedge clk)"))
      assert(!rtl.contains("posedge reset"))
      assert(!rtl.contains("negedge"))
    }
  }

  it should "reject VM configuration without supervisor support" in {
    intercept[IllegalArgumentException] {
      (new ChiselStage).emitChirrtl(new CSRStatus(false, true))
    }
  }
}
