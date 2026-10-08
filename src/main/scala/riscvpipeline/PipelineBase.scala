package riscvpipeline

import chisel3._
import chisel3.util.Cat
import riscvpipeline.config.CpuConfig
import riscvpipeline.ifu.IFU
import riscvpipeline.ieu.IEU
import riscvpipeline.lsu.LSU
import riscvpipeline.privileged.{InterruptInputs, Privileged}

/** 共用五级流水线连接；第七章顶层与第八章外部中断顶层共享同一实现。 */
abstract class PipelineBase(config: CpuConfig) extends RawModule {
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new RiscvPipelineIO(config))
  val Interrupts = Wire(new InterruptInputs)
  val ifu = Module(new IFU(config))
  val ieu = Module(new IEU(config))
  val lsu = Module(new LSU(config))

  ifu.clk := clk
  ifu.reset := reset
  val privileged = if (config.privilegedSupported) {
    Some(Module(new Privileged(config.privileged)))
  } else None
  val RedirectM = WireDefault(false.B)
  val RedirectPCM = WireDefault(0.U(32.W))
  val TrapM = WireDefault(false.B)
  val StallM = WireDefault(false.B)
  val CSRResultM = WireDefault(0.U(32.W))
  val BigEndianM = WireDefault(false.B)

  ifu.io.RedirectM := RedirectM
  ifu.io.RedirectPCM := RedirectPCM
  ifu.io.PCSrcE := ieu.io.PCSrcE
  ifu.io.IEUAdrE := ieu.io.IEUAdrE
  ifu.io.StallF := ieu.io.StallF
  ifu.io.StallD := ieu.io.StallD
  ifu.io.FlushD := ieu.io.FlushD
  ieu.clk := clk
  ieu.reset := reset
  ieu.io.InstrD := ifu.io.InstrD
  ieu.io.InstrAccessFaultD := ifu.io.InstrAccessFaultD
  ieu.io.StallM := StallM
  ieu.io.FlushM := RedirectM
  ieu.io.TrapM := TrapM
  ieu.io.CSRResultM := CSRResultM
  ieu.io.PCD := ifu.io.PCD
  ieu.io.PCPlus4D := ifu.io.PCPlus4D
  ieu.io.ValidD := ifu.io.ValidD
  ieu.io.ReadDataM := lsu.io.ReadDataM

  lsu.clk := clk
  lsu.reset := reset
  lsu.io.SuppressM := TrapM || StallM
  lsu.io.BigEndianM := BigEndianM
  lsu.io.MemReadE := ieu.io.MemRWE(1)
  lsu.io.Funct3E := ieu.io.Funct3E
  lsu.io.IEUAdrE := ieu.io.IEUAdrE
  lsu.io.MemRWM := ieu.io.MemRWM
  lsu.io.Funct3M := ieu.io.Funct3M
  lsu.io.IEUAdrM := ieu.io.IEUAdrM
  lsu.io.WriteDataM := ieu.io.WriteDataM

  privileged.foreach { p =>
    p.clk := clk
    p.reset := reset
    p.io.ValidM := ieu.io.ValidM
    p.io.StallW := StallM
    // 自身Trap/Ret重定向只杀年轻流水槽，不取消当前事件。
    p.io.FlushM := false.B
    p.io.CommittedM := false.B
    p.io.CommittedF := false.B
    p.io.wfiW := ieu.io.wfiW
    val InstrRetiredM = ieu.io.ValidM && !TrapM && !StallM && !reset
    p.io.InstrRetiredM := InstrRetiredM
    p.io.InstrM := ieu.io.InstrM
    p.io.PCM := ieu.io.PCM
    p.io.SrcAM := ieu.io.SrcAM
    p.io.IEUAdrM := ieu.io.IEUAdrM
    p.io.InstrAccessFaultM := ieu.io.InstrAccessFaultM
    p.io.InstrMisalignedFaultM := ieu.io.InstrMisalignedFaultM
    p.io.IllegalIEUInstrM := ieu.io.IllegalIEUInstrM
    p.io.LoadMisalignedFaultM := lsu.io.LoadMisalignedFaultM
    p.io.StoreAmoMisalignedFaultM := lsu.io.StoreAmoMisalignedFaultM
    p.io.LoadAccessFaultM := lsu.io.LoadAccessFaultM
    p.io.StoreAmoAccessFaultM := lsu.io.StoreAmoAccessFaultM
    p.io.InstrPageFaultM := false.B
    p.io.LoadPageFaultM := false.B
    p.io.StoreAmoPageFaultM := false.B
    p.io.BreakpointFaultM := false.B
    p.io.MExtInt := Interrupts.MExtInt
    p.io.SExtInt := Interrupts.SExtInt
    p.io.MTimerInt := Interrupts.MTimerInt
    p.io.MSwInt := Interrupts.MSwInt
    p.io.TIME_REGW := Interrupts.TIME_REGW
    p.io.HPMEventsM := Cat(0.U(27.W), ieu.io.StoreStallD,
      ieu.io.LoadStallD, ieu.io.MemRWM(0), ieu.io.MemRWM(1),
      InstrRetiredM)
    RedirectM := p.io.RedirectM
    RedirectPCM := p.io.RedirectPCM
    TrapM := p.io.TrapM
    StallM := p.io.WFIStallM
    CSRResultM := p.io.CSRResultM
    BigEndianM := p.io.BigEndianM
  }

  io.WriteData := ieu.io.WriteDataM
  io.IEUAdr := ieu.io.IEUAdrM
  io.MemRW := Mux(TrapM || StallM, 0.U(2.W), ieu.io.MemRWM)
  io.Funct3 := ieu.io.Funct3M
  io.ByteMask := lsu.io.ByteMaskM
  io.MemWrite := lsu.io.ByteMaskM.orR
}
