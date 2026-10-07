package riscvpipeline.privileged

import chisel3._
import chisel3.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.Files
import scala.util.Random

// 仅测试包装层带观察时钟；所有检查均不推进时钟。
private class CSRAccessHarness extends Module {
  val io = IO(new CSRAccessIO)
  private val dut = Module(new CSRAccess)
  io <> dut.io
}

class CSRAccessSpec extends AnyFlatSpec with ChiselScalatestTester {
  private val mask = BigInt("ffffffff", 16)
  private def hex(s: String): BigInt = BigInt(s, 16)

  private case class Access(
    funct3: Int = 2, adr: Int = 0x340, rd: Int = 1, field: Int = 1,
    src: BigInt = 0, old: BigInt = hex("87654321"), base: BigInt = hex("87654321"),
    mode: Int = 3, valid: Boolean = true, exists: Boolean = true,
    allowed: Boolean = true, opcode: Int = 0x73
  ) {
    def instr: BigInt = (BigInt(adr) << 20) | (BigInt(field) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | opcode
    def source: BigInt = if (funct3 >= 4) BigInt(field) else src
  }
  private case class Expected(
    read: Boolean, write: Boolean, value: BigInt = 0, result: BigInt = 0,
    illegal: Boolean = false, isCSR: Boolean = true
  )

  private def check(dut: CSRAccessHarness, a: Access, e: Expected): Unit = {
    withClue(s"access=$a expected=$e: ") {
      dut.io.InstrM.poke(a.instr.U)
      dut.io.ValidM.poke(a.valid.B)
      dut.io.PrivilegeModeW.poke(a.mode.U)
      dut.io.SrcAM.poke(a.src.U)
      dut.io.CSRReadValM.poke(a.old.U)
      dut.io.CSRModifyValM.poke(a.base.U)
      dut.io.CSRExistsM.poke(a.exists.B)
      dut.io.CSRAccessAllowedM.poke(a.allowed.B)
      dut.io.IsCSRInstrM.expect(e.isCSR.B)
      dut.io.CSRAdrM.expect(a.adr.U)
      dut.io.CSRSrcM.expect(a.source.U)
      dut.io.CSRReadM.expect(e.read.B)
      dut.io.CSRWriteM.expect(e.write.B)
      dut.io.CSRWriteValM.expect(e.value.U)
      dut.io.CSRResultM.expect(e.result.U)
      dut.io.IllegalCSRAccessM.expect(e.illegal.B)
    }
  }

  behavior of "CSRAccess"

  it should "match hand calculated values for all six instructions" in {
    test(new CSRAccessHarness) { dut =>
      // funct3、字段、寄存器数据、基值、手算写值；读取旧值固定为 87654321。
      val vectors = Seq(
        (1, 1, "00000000", "ffffffff", "00000000"),
        (1, 1, "ffffffff", "00000000", "ffffffff"),
        (1, 31, "80000000", "55555555", "80000000"),
        (2, 1, "0f0f0f0f", "a5a5a5a5", "afafafaf"),
        (2, 1, "80000000", "00000001", "80000001"),
        (2, 1, "ffffffff", "00000000", "ffffffff"),
        (3, 1, "0f0f0f0f", "a5a5a5a5", "a0a0a0a0"),
        (3, 1, "80000000", "ffffffff", "7fffffff"),
        (3, 1, "ffffffff", "ffffffff", "00000000"),
        (5, 0, "ffffffff", "ffffffff", "00000000"),
        (5, 1, "ffffffff", "00000000", "00000001"),
        (5, 31, "80000000", "a5a5a5a5", "0000001f"),
        (6, 1, "ffffffff", "80000000", "80000001"),
        (6, 31, "ffffffff", "a5a5a5a5", "a5a5a5bf"),
        (7, 1, "ffffffff", "ffffffff", "fffffffe"),
        (7, 31, "ffffffff", "a5a5a5a5", "a5a5a5a0")
      )
      vectors.foreach { case (f, field, src, base, value) =>
        check(dut, Access(funct3 = f, field = field, src = hex(src), base = hex(base)),
          Expected(true, true, hex(value), hex("87654321")))
      }
    }
  }

  it should "suppress RW reads only for rd zero and still write a zero source" in {
    test(new CSRAccessHarness) { dut =>
      for (f <- Seq(1, 5); rd <- Seq(0, 1); field <- Seq(0, 1, 31)) {
        val a = Access(funct3 = f, rd = rd, field = field, src = 0)
        check(dut, a, Expected(rd != 0, true, a.source,
          if (rd == 0) BigInt(0) else a.old))
      }
    }
  }

  it should "use source fields rather than source data to suppress RS and RC writes" in {
    test(new CSRAccessHarness) { dut =>
      for (f <- Seq(2, 3, 6, 7); rd <- Seq(0, 1)) {
        check(dut, Access(funct3 = f, rd = rd, field = 0),
          Expected(true, false, result = hex("87654321")))
      }
      for (f <- Seq(2, 3); field <- Seq(1, 31)) {
        check(dut, Access(funct3 = f, field = field, src = 0),
          Expected(true, true, hex("87654321"), hex("87654321")))
      }
    }
  }

  it should "permit read only accesses but reject every attempted read only write" in {
    test(new CSRAccessHarness) { dut =>
      for (adr <- Seq(0xc00, 0xd00, 0xf11); f <- Seq(2, 3, 6, 7)) {
        check(dut, Access(funct3 = f, adr = adr, field = 0),
          Expected(true, false, result = hex("87654321")))
        check(dut, Access(funct3 = f, adr = adr, field = 1, src = 0),
          Expected(false, false, illegal = true))
      }
      for (f <- Seq(1, 5); rd <- Seq(0, 1); field <- Seq(0, 1)) {
        check(dut, Access(funct3 = f, adr = 0xf11, rd = rd, field = field),
          Expected(false, false, illegal = true))
      }
    }
  }

  it should "enforce the U S M matrix and reject unsupported privilege encodings" in {
    test(new CSRAccessHarness) { dut =>
      // 每行对应当前模式 U/S/保留/M，每列对应地址最低模式 U/S/H/M。
      val permission = Seq(
        Seq(true, false, false, false), Seq(true, true, false, false),
        Seq(false, false, false, false), Seq(true, true, false, true)
      )
      for (mode <- 0 until 4; minimum <- 0 until 4; f <- Seq(1, 2, 3, 5, 6, 7)) {
        val a = Access(funct3 = f, mode = mode, adr = (minimum << 8) | 0x40,
          field = 0, rd = 0)
        val e = if (!permission(mode)(minimum)) Expected(false, false, illegal = true)
          else if (f == 1 || f == 5) Expected(false, true)
          else Expected(true, false, result = a.old)
        check(dut, a, e)
      }
    }
  }

  it should "check existence and additional permissions even with reads suppressed" in {
    test(new CSRAccessHarness) { dut =>
      for (f <- Seq(1, 2, 3, 5, 6, 7); rd <- Seq(0, 1);
           (exists, allowed) <- Seq((false, true), (true, false), (false, false))) {
        check(dut, Access(funct3 = f, rd = rd, field = 0,
          exists = exists, allowed = allowed), Expected(false, false, illegal = true))
      }
    }
  }

  it should "return software old values while modifying internal mip and sip state" in {
    test(new CSRAccessHarness) { dut =>
      for (adr <- Seq(0x344, 0x144)) {
        // 旧值含外部中断 0x80；内部可写状态仅 0x2。
        for ((f, expected) <- Seq(2 -> 3, 3 -> 2, 6 -> 3, 7 -> 2)) {
          check(dut, Access(funct3 = f, adr = adr, src = 1, old = 0x82, base = 2),
            Expected(true, true, expected, 0x82))
        }
      }
    }
  }

  it should "keep invalid and non CSR instructions inert without an access exception" in {
    test(new CSRAccessHarness) { dut =>
      for (f <- 0 until 8; opcode <- Seq(0x73, 0x13, 0x03, 0x6f);
           valid <- Seq(false, true)) {
        val decoded = opcode == 0x73 && Set(1, 2, 3, 5, 6, 7).contains(f)
        if (!valid || !decoded) {
          check(dut, Access(funct3 = f, opcode = opcode, valid = valid,
            mode = 2, exists = false, allowed = false, src = mask),
            Expected(false, false, isCSR = decoded))
        }
      }
    }
  }

  // 独立表指定读取/写入条件；逐位软件模型避免复用硬件 OR/AND 数据通路。
  private val operations = Map(
    1 -> ("replace", false, true), 2 -> ("set", false, false),
    3 -> ("clear", false, false), 5 -> ("replace", true, true),
    6 -> ("set", true, false), 7 -> ("clear", true, false)
  )
  private def reference(a: Access): Expected = {
    operations.get(a.funct3).filter(_ => a.opcode == 0x73) match {
      case None => Expected(false, false, isCSR = false)
      case Some((op, immediate, replace)) =>
        val read = !replace || a.rd != 0
        val write = replace || a.field != 0
        val modes = Map(0 -> Set(0), 1 -> Set(0, 1), 3 -> Set(0, 1, 3))
        val legal = a.exists && a.allowed &&
          modes.getOrElse(a.mode, Set.empty[Int]).contains((a.adr / 256) % 4) &&
          !(a.adr >= 0xc00 && write)
        val src = if (immediate) BigInt(a.field) else a.src
        val value = (0 until 32).foldLeft(BigInt(0)) { (acc, bit) =>
          val set = op match {
            case "replace" => src.testBit(bit)
            case "set" => a.base.testBit(bit) || src.testBit(bit)
            case "clear" => a.base.testBit(bit) && !src.testBit(bit)
          }
          if (set) acc.setBit(bit) else acc
        }
        Expected(a.valid && legal && read, a.valid && legal && write,
          if (a.valid && legal && write) value else BigInt(0),
          if (a.valid && legal && read) a.old else BigInt(0), a.valid && !legal)
    }
  }

  it should "match a fixed seed reference across all encodings and access conditions" in {
    test(new CSRAccessHarness) { dut =>
      val random = new Random(0x8c5a2026L)
      for (i <- 0 until 2048) {
        val a = Access(funct3 = i % 8, adr = random.nextInt(4096),
          rd = if (i % 3 == 0) 0 else random.nextInt(32),
          field = if (i % 3 == 0) 0 else random.nextInt(32),
          src = BigInt(32, random), old = BigInt(32, random), base = BigInt(32, random),
          mode = random.nextInt(4), valid = i % 7 != 0, exists = i % 11 != 0,
          allowed = i % 13 != 0, opcode = if (i % 5 == 0) 0x13 else 0x73)
        check(dut, a, reference(a))
      }
    }
  }

  it should "emit exactly the RV32 combinational interface with no state or clock" in {
    val dir = Files.createTempDirectory("csr-access-interface-")
    val rtl = (new ChiselStage).emitVerilog(new CSRAccess,
      Array("--target-dir", dir.toString))
    val pattern = "(?m)^\\s*(input|output)\\s+(?:\\[(\\d+):0\\]\\s+)?(\\w+)".r
    val ports = pattern.findAllMatchIn(rtl).map { p =>
      p.group(3) -> (p.group(1), Option(p.group(2)).map(_.toInt + 1).getOrElse(1))
    }.toMap
    val inputs = Map("InstrM" -> 32, "ValidM" -> 1, "PrivilegeModeW" -> 2,
      "SrcAM" -> 32, "CSRReadValM" -> 32, "CSRModifyValM" -> 32,
      "CSRExistsM" -> 1, "CSRAccessAllowedM" -> 1)
    val outputs = Map("IsCSRInstrM" -> 1, "CSRAdrM" -> 12, "CSRSrcM" -> 32,
      "CSRReadM" -> 1, "CSRWriteM" -> 1, "CSRWriteValM" -> 32,
      "CSRResultM" -> 32, "IllegalCSRAccessM" -> 1)
    assert(ports == inputs.map { case (n, w) => s"io_$n" -> ("input", w) } ++
      outputs.map { case (n, w) => s"io_$n" -> ("output", w) })
    assert(!rtl.contains("always @"))
    assert(!rtl.contains("reg "))
  }
}
