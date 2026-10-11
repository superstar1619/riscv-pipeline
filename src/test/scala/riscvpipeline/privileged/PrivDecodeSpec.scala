package riscvpipeline.privileged

import riscvpipeline.TestEncoding.{CSR, CSRFunction, Mask, Opcode, Privilege, SystemFunction}
import chisel3._
import chisel3.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.Files
import scala.util.Random
import scala.language.reflectiveCalls

private class PrivDecodeHarness(supportedS: Boolean = true, supportedVM: Boolean = false)
    extends Module {
  val io = IO(new PrivDecodeIO)
  private val dut = Module(new PrivDecode(supportedS, supportedVM))
  io <> dut.io
}

// 只在测试包装层连接两轮模块，生产 CPU 不接入特权单元。
private class CSRPrivDecodeHarness extends Module {
  val io = IO(new Bundle {
    val InstrM = Input(UInt(32.W))
    val ValidM = Input(Bool())
    val Mode = Input(UInt(2.W))
    val Exists = Input(Bool())
    val Allowed = Input(Bool())
    val Illegal = Output(Bool())
    val IllegalPriv = Output(Bool())
    val Read = Output(Bool())
    val Write = Output(Bool())
    val Ret = Output(Bool())
  })
  val access = Module(new CSRAccess)
  val decode = Module(new PrivDecode)
  access.io.InstrM := io.InstrM
  access.io.ValidM := io.ValidM
  access.io.PrivilegeModeW := io.Mode
  access.io.SrcAM := 0.U(32.W)
  access.io.CSRReadValM := 0.U(32.W)
  access.io.CSRModifyValM := 0.U(32.W)
  access.io.CSRExistsM := io.Exists
  access.io.CSRAccessAllowedM := io.Allowed
  decode.io.InstrM := io.InstrM
  decode.io.ValidM := io.ValidM
  decode.io.PrivilegeModeW := io.Mode
  decode.io.STATUS_TSR := false.B
  decode.io.STATUS_TVM := false.B
  decode.io.STATUS_TW := false.B
  decode.io.WFITimeoutM := false.B
  decode.io.IllegalIEUInstrM := false.B
  decode.io.IllegalCSRAccessM := access.io.IllegalCSRAccessM
  io.Illegal := decode.io.IllegalInstrFaultM
  io.IllegalPriv := decode.io.IllegalPrivilegedInstrM
  io.Read := access.io.CSRReadM
  io.Write := access.io.CSRWriteM
  io.Ret := decode.io.RetM
}

class PrivDecodeSpec extends AnyFlatSpec with ChiselScalatestTester {
  private def hex(s: String): BigInt = BigInt(s, 16)
  private val ecall = hex("00000073")
  private val ebreak = hex("00100073")
  private val sret = hex("10200073")
  private val mret = hex("30200073")
  private val wfi = hex("10500073")
  private val sfence = hex("12000073")
  private val csrCodes = Set(CSRFunction.RW, CSRFunction.RS, CSRFunction.RC,
    CSRFunction.RWI, CSRFunction.RSI, CSRFunction.RCI)

  private case class Input(
    instr: BigInt, mode: Int = Privilege.MACHINE, valid: Boolean = true, tsr: Boolean = false,
    tvm: Boolean = false, tw: Boolean = false, timeout: Boolean = false,
    ieuIllegal: Boolean = false, csrIllegal: Boolean = false
  )
  private case class Expected(
    ecall: Boolean = false, breakpoint: Boolean = false, sret: Boolean = false,
    mret: Boolean = false, wfi: Boolean = false, sfence: Boolean = false,
    illegalPriv: Boolean = false, illegal: Boolean = false
  )
  private val localIllegal = Expected(illegalPriv = true, illegal = true)

  private def check(dut: PrivDecodeHarness, a: Input, e: Expected): Unit = {
    withClue(s"input=$a expected=$e: ") {
      dut.io.InstrM.poke(a.instr.U)
      dut.io.ValidM.poke(a.valid.B)
      dut.io.PrivilegeModeW.poke(a.mode.U)
      dut.io.STATUS_TSR.poke(a.tsr.B)
      dut.io.STATUS_TVM.poke(a.tvm.B)
      dut.io.STATUS_TW.poke(a.tw.B)
      dut.io.WFITimeoutM.poke(a.timeout.B)
      dut.io.IllegalIEUInstrM.poke(a.ieuIllegal.B)
      dut.io.IllegalCSRAccessM.poke(a.csrIllegal.B)
      dut.io.EcallFaultM.expect(e.ecall.B)
      dut.io.BreakpointFaultM.expect(e.breakpoint.B)
      dut.io.sretM.expect(e.sret.B)
      dut.io.mretM.expect(e.mret.B)
      dut.io.RetM.expect((e.sret || e.mret).B)
      dut.io.wfiM.expect(e.wfi.B)
      dut.io.sfencevmaM.expect(e.sfence.B)
      dut.io.IllegalPrivilegedInstrM.expect(e.illegalPriv.B)
      dut.io.IllegalInstrFaultM.expect(e.illegal.B)
    }
  }

  // 软件按完整字模式匹配，再查允许的模式集合，不调用硬件逻辑。
  private def reference(a: Input, s: Boolean, vm: Boolean): Expected = {
    if (!a.valid) return Expected()
    val system = a.instr % 128 == Opcode.SYSTEM
    val funct3 = ((a.instr / 4096) % 8).toInt
    val isCSR = system && csrCodes.contains(funct3)
    val supported = Set(Privilege.USER, Privilege.MACHINE).contains(a.mode) || (s &&
      a.mode == Privilege.SUPERVISOR)
    val sfenceWords = (a.instr & Mask.SFENCE_VMA) == sfence
    val kind = Map(ecall -> "ecall", ebreak -> "break", sret -> "sret",
      mret -> "mret", wfi -> "wfi").get(a.instr)
      .orElse(if (sfenceWords) Some("sfence") else None)
    val permitted = kind.exists {
      case "ecall" | "break" | "wfi" => supported
      case "mret" => a.mode == Privilege.MACHINE
      case "sret" => s && (a.mode == Privilege.MACHINE || (a.mode == Privilege.SUPERVISOR &&
        !a.tsr))
      case "sfence" => vm && s && (a.mode == Privilege.MACHINE ||
        (a.mode == Privilege.SUPERVISOR && !a.tvm))
    }
    val local = system && (!supported || (!isCSR && !permitted))
    val expired = kind.contains("wfi") && supported && a.mode != Privilege.MACHINE &&
      a.timeout && (a.tw || (s && a.mode == Privilege.USER))
    val fault = local || expired || a.ieuIllegal || a.csrIllegal
    val active = permitted && !fault
    Expected(active && kind.contains("ecall"), active && kind.contains("break"),
      active && kind.contains("sret"), active && kind.contains("mret"),
      kind.contains("wfi") && supported && !a.ieuIllegal && !a.csrIllegal,
      active && kind.contains("sfence"), local, fault)
  }

  behavior of "PrivDecode"

  it should "decode the five exact base privileged words in machine mode" in {
    test(new PrivDecodeHarness) { dut =>
      val cases = Seq(ecall -> Expected(ecall = true),
        ebreak -> Expected(breakpoint = true), mret -> Expected(mret = true),
        sret -> Expected(sret = true), wfi -> Expected(wfi = true),
        sfence -> localIllegal)
      cases.foreach { case (word, expected) => check(dut, Input(word), expected) }
    }
  }

  it should "enforce return permissions and apply TSR only to supervisor SRET" in {
    test(new PrivDecodeHarness) { dut =>
      for (tsr <- Seq(false, true)) {
        val rows = Seq(
          (Privilege.USER, localIllegal, localIllegal),
          (Privilege.SUPERVISOR, localIllegal,
            if (tsr) localIllegal else Expected(sret = true)),
          (Privilege.RESERVED, localIllegal, localIllegal),
          (Privilege.MACHINE, Expected(mret = true), Expected(sret = true))
        )
        rows.foreach { case (mode, m, s) =>
          check(dut, Input(mret, mode = mode, tsr = tsr), m)
          check(dut, Input(sret, mode = mode, tsr = tsr), s)
        }
      }
      for (mode <- Seq(Privilege.USER, Privilege.SUPERVISOR, Privilege.MACHINE)) {
        check(dut, Input(ecall, mode), Expected(ecall = true))
        check(dut, Input(ebreak, mode), Expected(breakpoint = true))
      }
      check(dut, Input(ecall, Privilege.RESERVED), localIllegal)
      check(dut, Input(ebreak, Privilege.RESERVED), localIllegal)
    }
  }

  it should "gate WFI timeouts by the instruction mode TW and S support" in {
    // 三行分别为U/S/M，列为(TW=0,超时0/1)、(TW=1,超时0/1)。
    val withS = Map(Privilege.USER -> Seq(false, true, false, true),
      Privilege.SUPERVISOR -> Seq(false, false, false, true),
        Privilege.MACHINE -> Seq(false, false, false, false))
    val withoutS = Map(Privilege.USER -> Seq(false, false, false, true),
      Privilege.MACHINE -> Seq(false, false, false, false))
    for (s <- Seq(false, true)) {
      test(new PrivDecodeHarness(s)) { dut =>
        val table = if (s) withS else withoutS
        for ((mode, results) <- table; tw <- Seq(false, true);
             expired <- Seq(false, true)) {
          val column = (if (tw) 2 else 0) + (if (expired) 1 else 0)
          check(dut, Input(wfi, mode, tw = tw, timeout = expired),
            Expected(wfi = true, illegal = results(column)))
        }
        check(dut, Input(wfi, Privilege.RESERVED, timeout = true, tw = true), localIllegal)
        if (!s) check(dut, Input(wfi, Privilege.SUPERVISOR), localIllegal)
        check(dut, Input(mret, tw = true, timeout = true), Expected(mret = true))
        check(dut, Input(ecall, Privilege.USER, tw = true, timeout = true),
          Expected(ecall = true))
      }
    }
  }

  it should "decode all SFENCE register fields and apply TVM only in supervisor mode" in {
    test(new PrivDecodeHarness(true, true)) { dut =>
      for (rs1 <- 0 until 32; rs2 <- 0 until 32) {
        val word = sfence | (BigInt(rs1) << 15) | (BigInt(rs2) << 20)
        check(dut, Input(word, tvm = true), Expected(sfence = true))
        check(dut, Input(word, Privilege.SUPERVISOR), Expected(sfence = true))
      }
      for (mode <- 0 until 4; tvm <- Seq(false, true)) {
        val expected = if (mode == Privilege.MACHINE || (mode == Privilege.SUPERVISOR && !tvm))
          Expected(sfence = true) else localIllegal
        check(dut, Input(sfence, mode, tvm = tvm), expected)
      }
    }
  }

  it should "reject S operations and S mode when supervisor support is disabled" in {
    test(new PrivDecodeHarness(false)) { dut =>
      for (mode <- 0 until 4) {
        check(dut, Input(sret, mode), localIllegal)
        check(dut, Input(sfence, mode), localIllegal)
      }
      for (word <- Seq(ecall, ebreak, mret, wfi, hex("34001073"))) {
        check(dut, Input(word, Privilege.SUPERVISOR), localIllegal)
      }
      check(dut, Input(ecall, Privilege.USER), Expected(ecall = true))
      check(dut, Input(mret), Expected(mret = true))
    }
  }

  it should "reject every nonzero rd and reserved rs1 in fixed privileged instructions" in {
    test(new PrivDecodeHarness(true, true)) { dut =>
      for (word <- Seq(ecall, ebreak, mret, sret, wfi); field <- 1 until 32) {
        check(dut, Input(word | (BigInt(field) << 7)), localIllegal)
        check(dut, Input(word | (BigInt(field) << 15)), localIllegal)
      }
      for (field <- 1 until 32) {
        check(dut, Input(sfence | (BigInt(field) << 7)), localIllegal)
      }
    }
  }

  it should "classify all funct12 values and reject unsupported SYSTEM extensions" in {
    test(new PrivDecodeHarness) { dut =>
      val known = Map(SystemFunction.ECALL -> Expected(ecall = true),
        SystemFunction.EBREAK -> Expected(breakpoint = true),
        SystemFunction.SRET -> Expected(sret = true), SystemFunction.MRET -> Expected(mret = true),
        SystemFunction.WFI -> Expected(wfi = true))
      for (f <- 0 until 4096) {
        check(dut, Input((BigInt(f) << 20) | Opcode.SYSTEM), known.getOrElse(f, localIllegal))
      }
      // URET、SINVAL.VMA、SFENCE.W.INVAL、SFENCE.INVAL.IR、保留funct3=100。
      for (word <- Seq("00200073", "16000073", "18000073", "18100073", "00004073")) {
        check(dut, Input(hex(word)), localIllegal)
      }
    }
  }

  it should "keep non SYSTEM instructions inert and aggregate faults only while valid" in {
    test(new PrivDecodeHarness) { dut =>
      for (word <- Seq(ecall, ebreak, mret, sret, wfi, sfence, hex("00000013"));
           ieu <- Seq(false, true); csr <- Seq(false, true)) {
        check(dut, Input(word, valid = false, timeout = true, tw = true,
          ieuIllegal = ieu, csrIllegal = csr), Expected())
        if (ieu || csr) {
          check(dut, Input(word, ieuIllegal = ieu, csrIllegal = csr),
            Expected(illegal = true, illegalPriv = word == sfence))
        }
      }
      for (opcode <- 0 until 128 if opcode != Opcode.SYSTEM) {
        check(dut, Input((mret & hex("ffffff80")) | opcode, mode = Privilege.RESERVED,
          tsr = true, tvm = true, tw = true, timeout = true), Expected())
      }
    }
  }

  it should "route all six CSR encodings to CSRAccess without privileged false positives" in {
    test(new CSRPrivDecodeHarness) { dut =>
      for (f <- Seq(CSRFunction.RW, CSRFunction.RS, CSRFunction.RC, CSRFunction.RWI,
        CSRFunction.RSI, CSRFunction.RCI); rd <- Seq(0, 1);
           field <- Seq(0, 1); valid <- Seq(false, true);
           (adr, mode, exists, allowed, legal) <- Seq(
             (CSR.MSCRATCH, 3, true, true, true), (CSR.MSCRATCH, 0, true, true, false),
             (CSR.MVENDORID, 3, true, true, f != CSRFunction.RW && f != CSRFunction.RWI &&
               field == 0),
             (CSR.MSCRATCH, 3, false, true, false), (CSR.MSCRATCH, 3, true, false, false))) {
        val word = (BigInt(adr) << 20) | (BigInt(field) << 15) |
          (BigInt(f) << 12) | (BigInt(rd) << 7) | Opcode.SYSTEM
        dut.io.InstrM.poke(word.U)
        dut.io.ValidM.poke(valid.B)
        dut.io.Mode.poke(mode.U)
        dut.io.Exists.poke(exists.B)
        dut.io.Allowed.poke(allowed.B)
        dut.io.Illegal.expect((valid && !legal).B)
        dut.io.IllegalPriv.expect(false.B)
        dut.io.Read.expect((valid && legal && (!(f == CSRFunction.RW ||
          f == CSRFunction.RWI) || rd != 0)).B)
        dut.io.Write.expect((valid && legal && (f == CSRFunction.RW ||
          f == CSRFunction.RWI || field != 0)).B)
        dut.io.Ret.expect(false.B)
      }
    }
  }

  it should "match an independent fixed seed reference for encodings and flags" in {
    for ((s, vm) <- Seq((false, false), (true, false), (true, true))) {
      test(new PrivDecodeHarness(s, vm)) { dut =>
        val random = new Random(0x8dec2026L)
        val words = Seq(ecall, ebreak, mret, sret, wfi, sfence,
          hex("00004073"), hex("34001073"), hex("00000013"))
        for (i <- 0 until 2048) {
          val base = words(i % words.size)
          val word = i % 4 match {
            case 0 => base
            case 1 => base ^ (BigInt(1) << random.nextInt(32))
            case 2 => (BigInt(25, random) << 7) | Opcode.SYSTEM
            case _ => BigInt(32, random)
          }
          val a = Input(word, mode = random.nextInt(4), valid = i % 7 != 0,
            tsr = random.nextBoolean(), tvm = random.nextBoolean(),
            tw = random.nextBoolean(), timeout = random.nextBoolean(),
            ieuIllegal = i % 13 == 0, csrIllegal = i % 17 == 0)
          check(dut, a, reference(a, s, vm))
        }
      }
    }
  }

  it should "emit the same combinational interface in all supported configurations" in {
    val inputs = Map("InstrM" -> 32, "ValidM" -> 1, "PrivilegeModeW" -> 2,
      "STATUS_TSR" -> 1, "STATUS_TVM" -> 1, "STATUS_TW" -> 1,
      "WFITimeoutM" -> 1, "IllegalIEUInstrM" -> 1, "IllegalCSRAccessM" -> 1)
    val outputs = Seq("EcallFaultM", "BreakpointFaultM", "sretM", "mretM", "RetM",
      "wfiM", "sfencevmaM", "IllegalPrivilegedInstrM", "IllegalInstrFaultM")
    for ((s, vm) <- Seq((false, false), (true, false), (true, true))) {
      val dir = Files.createTempDirectory("privdecode-interface-")
      val rtl = (new ChiselStage).emitVerilog(new PrivDecode(s, vm),
        Array("--target-dir", dir.toString))
      val pattern = "(?m)^\\s*(input|output)\\s+(?:\\[(\\d+):0\\]\\s+)?(\\w+)".r
      val ports = pattern.findAllMatchIn(rtl).map { p =>
        p.group(3) -> (p.group(1), Option(p.group(2)).map(_.toInt + 1).getOrElse(1))
      }.toMap
      assert(ports == inputs.map { case (n, w) => s"io_$n" -> ("input", w) } ++
        outputs.map(n => s"io_$n" -> ("output", 1)).toMap)
      assert(!rtl.contains("always @"))
      assert(!rtl.contains("reg "))
    }
  }

  it should "reject virtual memory decoding without supervisor support" in {
    val error = intercept[IllegalArgumentException] {
      (new ChiselStage).emitChirrtl(new PrivDecode(false, true))
    }
    assert(error.getMessage.contains("virtual memory decoding requires supervisor support"))
  }
}
