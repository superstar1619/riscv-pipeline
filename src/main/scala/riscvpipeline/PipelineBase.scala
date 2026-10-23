package riscvpipeline

import chisel3._
import riscvpipeline.config.CpuConfig
import riscvpipeline.control.ControlEncoding._
import riscvpipeline.isa.Architecture.XLEN
import riscvpipeline.isa.InstructionDecode
import riscvpipeline.ebu.{AHBMasterIO, EBU}
import riscvpipeline.hazard.Hazard
import riscvpipeline.ifu.IFU
import riscvpipeline.ieu.IEU
import riscvpipeline.lsu.LSU
import riscvpipeline.privileged.{InterruptInputs, Privileged}
import riscvpipeline.privileged.PrivilegedConstants.Counter

/** 共用五级流水线连接；第七章顶层与第八章外部中断顶层共享同一实现。 */
abstract class PipelineBase(config: CpuConfig) extends RawModule {
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new RiscvPipelineIO(config))
  val Interrupts = Wire(new InterruptInputs)
  val ifu = Module(new IFU(config))
  val ieu = Module(new IEU(config))
  val lsu = Module(new LSU(config))
  val hazard = Module(new Hazard)

  ifu.clk := clk
  ifu.reset := reset
  val privileged = if (config.privilegedSupported) {
    Some(Module(new Privileged(config.privileged)))
  } else None
  val RedirectM = WireDefault(false.B)
  val RedirectPCM = WireDefault(0.U(XLEN.W))
  val TrapM = WireDefault(false.B)
  val StallM = WireDefault(false.B)
  val CSRResultM = WireDefault(0.U(XLEN.W))
  val BigEndianM = WireDefault(false.B)
  val WFIStallM = WireDefault(false.B)
  val RedirectIntentM = WireDefault(false.B)
  val ExternalBus = config.bus.map { _ =>
    val external = Wire(new AHBMasterIO)
    external.HREADY := true.B
    external.HRDATA := 0.U
    external.HRESP := false.B
    external
  }

  ifu.io.RedirectM := RedirectM
  ifu.io.RedirectPCM := RedirectPCM
  ifu.io.PCSrcE := ieu.io.PCSrcE
  ifu.io.IEUAdrE := ieu.io.IEUAdrE
  ifu.io.StallF := hazard.io.StallF
  ifu.io.StallD := hazard.io.StallD
  ifu.io.FlushD := hazard.io.FlushD
  ifu.io.BusFlushF := hazard.io.FlushBusF
  ifu.io.ahb.HREADY := true.B
  ifu.io.ahb.HRDATA := 0.U
  ifu.io.ahb.HRESP := false.B
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
  lsu.io.StallM := StallM
  lsu.io.BusFlushM := hazard.io.FlushBusM
  lsu.io.ahb.HREADY := true.B
  lsu.io.ahb.HRDATA := 0.U
  lsu.io.ahb.HRESP := false.B
  lsu.io.BigEndianM := BigEndianM
  lsu.io.MemReadE := ieu.io.MemRWE(MEM_READ_BIT)
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
    p.io.CommittedM := (if (config.bus.nonEmpty) lsu.io.BusCommittedM else false.B)
    p.io.CommittedF := (if (config.bus.nonEmpty) ifu.io.BusCommittedF else false.B)
    p.io.wfiW := ieu.io.wfiW
    val InstrRetiredM = ieu.io.ValidM && !TrapM && !StallM
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
    // 事件位编号与计数器选择器约定对应，未实现的事件固定为零。
    val HPMEventsM = WireDefault(VecInit(Seq.fill(Counter.EVENT_COUNT)(false.B)))
    HPMEventsM(Counter.EVENT_RETIRED) := InstrRetiredM
    HPMEventsM(Counter.EVENT_LOAD) := InstrRetiredM && ieu.io.MemRWM(MEM_READ_BIT)
    HPMEventsM(Counter.EVENT_STORE) := InstrRetiredM && ieu.io.MemRWM(MEM_WRITE_BIT)
    HPMEventsM(Counter.EVENT_LOAD_STALL) := hazard.io.LoadStallD
    HPMEventsM(Counter.EVENT_STORE_STALL) := hazard.io.StoreStallD
    p.io.HPMEventsM := HPMEventsM.asUInt
    RedirectM := p.io.RedirectM
    RedirectPCM := p.io.RedirectPCM
    TrapM := p.io.TrapM
    WFIStallM := p.io.WFIStallM
    // 候选不依赖最终 StallM，先取消未接受取指，再提交 M 级重定向。
    RedirectIntentM := ieu.io.ValidM && (p.io.TrapM || p.io.RetM ||
      p.io.CSRWriteM || (InstructionDecode.isFence(ieu.io.InstrM) &&
        !p.io.IllegalInstrFaultM))
    CSRResultM := p.io.CSRResultM
    BigEndianM := p.io.BigEndianM
  }

  // One combinational controller for local-memory and bus configurations.
  hazard.io.ValidD := ifu.io.ValidD && !reset
  hazard.io.Rs1D := ieu.io.Rs1D
  hazard.io.Rs2D := ieu.io.Rs2D
  hazard.io.UsesRs1D := ieu.io.UsesRs1D
  hazard.io.UsesRs2D := ieu.io.UsesRs2D
  hazard.io.MemRWD := ieu.io.MemRWD
  hazard.io.Rs1E := ieu.io.Rs1E
  hazard.io.Rs2E := ieu.io.Rs2E
  hazard.io.RdE := ieu.io.RdE
  hazard.io.ValidE := ieu.io.ValidE
  hazard.io.MemRWE := ieu.io.MemRWE
  hazard.io.LateResultE := ieu.io.LateResultE
  hazard.io.RdM := ieu.io.RdM
  hazard.io.RegWriteM := ieu.io.RegWriteM
  hazard.io.ValidM := ieu.io.ValidM
  hazard.io.ResultSrcM := ieu.io.ResultSrcM
  hazard.io.RdW := ieu.io.RdW
  hazard.io.RegWriteW := ieu.io.RegWriteW
  hazard.io.ValidW := ieu.io.ValidW
  hazard.io.PCSrcE := ieu.io.PCSrcE
  hazard.io.IFUStallF := (if (config.bus.nonEmpty) ifu.io.BusStallF else false.B)
  hazard.io.LSUStallM := (if (config.bus.nonEmpty) lsu.io.BusStallM else false.B)
  hazard.io.CommittedF := (if (config.bus.nonEmpty) ifu.io.BusCommittedF else false.B)
  hazard.io.CommittedM := (if (config.bus.nonEmpty) lsu.io.BusCommittedM else false.B)
  hazard.io.TrapM := TrapM
  hazard.io.RedirectIntentM := RedirectIntentM
  hazard.io.RedirectM := RedirectM
  hazard.io.WFIStallM := WFIStallM
  ieu.io.ForwardAE := hazard.io.ForwardAE
  ieu.io.ForwardBE := hazard.io.ForwardBE
  ieu.io.FlushE := hazard.io.FlushE
  StallM := hazard.io.StallM
  val ebu = config.bus.map { _ =>
    val master = Module(new EBU)
    master.clk := clk
    master.reset := reset
    master.io.IFUHTRANS := ifu.io.ahb.HTRANS
    master.io.IFUHSIZE := ifu.io.ahb.HSIZE
    master.io.IFUHBURST := ifu.io.ahb.HBURST
    master.io.IFUHADDR := ifu.io.ahb.HADDR
    master.io.LSUHTRANS := lsu.io.ahb.HTRANS
    master.io.LSUHWRITE := lsu.io.ahb.HWRITE
    master.io.LSUHSIZE := lsu.io.ahb.HSIZE
    master.io.LSUHBURST := lsu.io.ahb.HBURST
    master.io.LSUHADDR := lsu.io.ahb.HADDR
    master.io.LSUHWDATA := lsu.io.ahb.HWDATA
    master.io.LSUHWSTRB := lsu.io.ahb.HWSTRB
    ifu.io.ahb.HREADY := master.io.IFUHREADY
    ifu.io.ahb.HRDATA := master.io.IFUHRDATA
    lsu.io.ahb.HREADY := master.io.LSUHREADY
    lsu.io.ahb.HRDATA := master.io.LSUHRDATA
    val external = ExternalBus.get
    master.io.HREADY := external.HREADY
    master.io.HRDATA := external.HRDATA
    master.io.HRESP := external.HRESP
    external.HADDR := master.io.HADDR
    external.HWDATA := master.io.HWDATA
    external.HWSTRB := master.io.HWSTRB
    external.HWRITE := master.io.HWRITE
    external.HSIZE := master.io.HSIZE
    external.HBURST := master.io.HBURST
    external.HPROT := master.io.HPROT
    external.HTRANS := master.io.HTRANS
    external.HMASTLOCK := master.io.HMASTLOCK

    master
  }

  io.WriteData := ieu.io.WriteDataM
  io.IEUAdr := ieu.io.IEUAdrM
  io.MemRW := Mux(TrapM || StallM, MEM_NONE.U(MEM_WIDTH.W), ieu.io.MemRWM)
  io.Funct3 := ieu.io.Funct3M
  io.ByteMask := lsu.io.ByteMaskM
  io.MemWrite := lsu.io.ByteMaskM.orR
}
