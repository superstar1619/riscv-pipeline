package riscvpipeline.ifu

import chisel3._
import chisel3.util.experimental.loadMemoryFromFileInline
import chisel3.util.log2Ceil
import riscvpipeline.config.CpuConfig
import riscvpipeline.isa.Architecture._

/** 字节地址和同步读响应有效控制；en=0 保持最后有效读结果，a/rd 名称沿用基线。 */
final class IROMIO(config: CpuConfig) extends Bundle {
  val a = Input(UInt(config.xlen.W))
  val en = Input(Bool())
  val rd = Output(UInt(INSTRUCTION_WIDTH.W))
}

/** 教材 §7.2.1 的同步 IROM，参考 CVW irom.sv 的同步读取和输出保持。
  * 地址在上升沿采样，输出直接属于 D 级；禁止重复寄存引入额外流水级。
  * 物理 CE 常开，en 只限定响应有效性；最后有效响应保持输出，不延迟有效响应。
  * 容量内字索引回绕；镜像加载不随 CPU 复位重载，首次读取前输出未指定。
  */
final class IROM(val config: CpuConfig = CpuConfig()) extends RawModule {
  val clk = IO(Input(Clock()))
  val io = IO(new IROMIO(config))
  private val indexWidth = log2Ceil(config.imemDepth)
  val WordIndex = io.a(indexWidth + INSTRUCTION_ALIGNMENT_BITS - 1, INSTRUCTION_ALIGNMENT_BITS)
  val ROM = withClock(clk) { SyncReadMem(config.imemDepth, UInt(INSTRUCTION_WIDTH.W)) }
  config.instructionInitFile.foreach(path => loadMemoryFromFileInline(ROM, path))
  val ReadData = ROM.read(WordIndex, true.B, clk)
  val ReadIssued = withClock(clk) { RegNext(io.en) }
  val HeldData = withClock(clk) { Reg(UInt(INSTRUCTION_WIDTH.W)) }
  when(ReadIssued) {
    HeldData := ReadData
  }
  io.rd := Mux(ReadIssued, ReadData, HeldData)
}
