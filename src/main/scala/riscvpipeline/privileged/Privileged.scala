package riscvpipeline.privileged

import chisel3._
import riscvpipeline.isa.Architecture._
import riscvpipeline.isa.InstructionDecode
import PrivilegedConstants.ExceptionCause._
import PrivilegedConstants.Privilege._
import PrivilegedConstants._

/** M级事件与外部同域中断；FlushM取消当前事件，不接自身产生的流水线冲刷。 */
final class PrivilegedIO extends Bundle {
  val ValidM = Input(Bool())
  val StallW = Input(Bool())
  val FlushM = Input(Bool())
  val CommittedM = Input(Bool())
  val CommittedF = Input(Bool())
  val wfiW = Input(Bool())
  val InstrRetiredM = Input(Bool())
  val MExtInt = Input(Bool())
  val SExtInt = Input(Bool())
  val MTimerInt = Input(Bool())
  val MSwInt = Input(Bool())
  val InstrMisalignedFaultM = Input(Bool())
  val InstrAccessFaultM = Input(Bool())
  val InstrPageFaultM = Input(Bool())
  val IllegalIEUInstrM = Input(Bool())
  val BreakpointFaultM = Input(Bool())
  val LoadMisalignedFaultM = Input(Bool())
  val StoreAmoMisalignedFaultM = Input(Bool())
  val LoadAccessFaultM = Input(Bool())
  val StoreAmoAccessFaultM = Input(Bool())
  val LoadPageFaultM = Input(Bool())
  val StoreAmoPageFaultM = Input(Bool())
  val InstrM = Input(UInt(INSTRUCTION_WIDTH.W))
  val PCM = Input(UInt(XLEN.W))
  val IEUAdrM = Input(UInt(XLEN.W))
  val SrcAM = Input(UInt(XLEN.W))
  val TIME_REGW = Input(UInt(Counter.WIDTH.W))
  val HPMEventsM = Input(UInt(Counter.EVENT_COUNT.W))

  val TrapM = Output(Bool())
  val InterruptM = Output(Bool())
  val ExceptionM = Output(Bool())
  val RetM = Output(Bool())
  val wfiM = Output(Bool())
  val WFIStallM = Output(Bool())
  val WFITimeoutM = Output(Bool())
  val IsCSRInstrM = Output(Bool())
  val CSRReadM = Output(Bool())
  val CSRWriteM = Output(Bool())
  val IllegalInstrFaultM = Output(Bool())
  val RedirectM = Output(Bool())
  val BigEndianM = Output(Bool())
  val CSRResultM = Output(UInt(XLEN.W))
  val PrivilegeModeW = Output(UInt(MODE_WIDTH.W))
  val NextPrivilegeModeM = Output(UInt(MODE_WIDTH.W))
  val RedirectPCM = Output(UInt(XLEN.W))
  val CauseM = Output(UInt(CAUSE_WIDTH.W))
  val MSTATUS_REGW = Output(UInt(XLEN.W))
  val MCYCLE_REGW = Output(UInt(Counter.WIDTH.W))
  val MINSTRET_REGW = Output(UInt(Counter.WIDTH.W))
}

/** 教材§8.4特权单元，RV32/Bare；显式clk、高有效同步reset。
  * 寄存器/模式使用沿前事件同拍更新，候选与实际重定向提交分别输出。
  */
final class Privileged(config: PrivilegedConfig = PrivilegedConfig()) extends RawModule {
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new PrivilegedIO)

  val csr = Module(new CSR(config))
  val mode = Module(new PrivMode(config.supervisorSupported))
  val decode = Module(new PrivDecode(config.supervisorSupported))
  val trap = Module(new Trap(config.supervisorSupported))
  val wfi = Module(new WFI(config.wfiTimeoutCycles))
  csr.clk := clk
  mode.clk := clk
  wfi.clk := clk
  csr.reset := reset
  mode.reset := reset
  wfi.reset := reset
  val ActiveM = io.ValidM && !io.FlushM
  // 候选Trap可清WFI计数，但外部停顿期间不能丢失已经到期的异常。
  // 仅寄存器采样StallW，避免StallW与WFIStall之间的组合反馈。
  val TimeoutHeldM = withClockAndReset(clk, reset) { RegInit(false.B) }
  withClockAndReset(clk, reset) {
    when(!ActiveM || !decode.io.wfiM || !io.StallW) {
      TimeoutHeldM := false.B
    }.elsewhen(wfi.io.WFITimeoutM) {
      TimeoutHeldM := true.B
    }
  }
  val WFITimeoutM = ActiveM && decode.io.wfiM &&
    (wfi.io.WFITimeoutM || TimeoutHeldM)

  csr.io.InstrM := io.InstrM
  csr.io.PCM := io.PCM
  csr.io.IEUAdrM := io.IEUAdrM
  csr.io.SrcAM := io.SrcAM
  csr.io.TIME_REGW := io.TIME_REGW
  csr.io.HPMEventsM := io.HPMEventsM
  csr.io.StallW := io.StallW
  csr.io.FlushM := io.FlushM
  csr.io.InstrRetiredM := io.InstrRetiredM
  csr.io.MExtInt := io.MExtInt
  csr.io.SExtInt := io.SExtInt
  csr.io.MTimerInt := io.MTimerInt
  csr.io.MSwInt := io.MSwInt
  csr.io.ValidM := ActiveM
  csr.io.TrapM := trap.io.TrapM
  csr.io.InterruptM := trap.io.InterruptM
  csr.io.CauseM := trap.io.CauseM
  csr.io.PrivilegeModeW := mode.io.PrivilegeModeW
  csr.io.NextPrivilegeModeM := mode.io.NextPrivilegeModeM
  csr.io.mretM := decode.io.mretM
  csr.io.sretM := decode.io.sretM

  mode.io.ValidM := ActiveM
  mode.io.StallW := io.StallW
  mode.io.FlushM := io.FlushM
  mode.io.TrapM := trap.io.TrapM
  mode.io.DelegateM := trap.io.DelegateM
  mode.io.mretM := decode.io.mretM
  mode.io.sretM := decode.io.sretM
  mode.io.STATUS_MPP := csr.io.STATUS_MPP
  mode.io.STATUS_SPP := csr.io.STATUS_SPP
  mode.io.STATUS_TSR := csr.io.STATUS_TSR

  decode.io.InstrM := io.InstrM
  decode.io.ValidM := ActiveM
  decode.io.PrivilegeModeW := mode.io.PrivilegeModeW
  decode.io.STATUS_TSR := csr.io.STATUS_TSR
  decode.io.STATUS_TVM := csr.io.STATUS_TVM
  decode.io.STATUS_TW := csr.io.STATUS_TW
  decode.io.WFITimeoutM := WFITimeoutM
  decode.io.IllegalIEUInstrM := io.IllegalIEUInstrM
  decode.io.IllegalCSRAccessM := csr.io.IllegalCSRAccessM

  trap.io.reset := reset
  trap.io.ValidM := ActiveM
  trap.io.CommittedM := io.CommittedM
  trap.io.CommittedF := io.CommittedF
  trap.io.wfiM := decode.io.wfiM
  trap.io.wfiW := io.wfiW
  trap.io.PrivilegeModeW := mode.io.PrivilegeModeW
  trap.io.STATUS_MIE := csr.io.STATUS_MIE
  trap.io.STATUS_SIE := csr.io.STATUS_SIE
  trap.io.MIP_REGW := csr.io.MIP_REGW
  trap.io.MIE_REGW := csr.io.MIE_REGW
  trap.io.MIDELEG_REGW := csr.io.MIDELEG_REGW
  trap.io.MEDELEG_REGW := csr.io.MEDELEG_REGW
  trap.io.IllegalInstrFaultM := decode.io.IllegalInstrFaultM
  trap.io.BreakpointFaultM := decode.io.BreakpointFaultM || io.BreakpointFaultM
  trap.io.EcallFaultM := decode.io.EcallFaultM
  trap.io.InstrMisalignedFaultM := io.InstrMisalignedFaultM
  trap.io.InstrAccessFaultM := io.InstrAccessFaultM
  trap.io.InstrPageFaultM := io.InstrPageFaultM
  trap.io.LoadMisalignedFaultM := io.LoadMisalignedFaultM
  trap.io.StoreAmoMisalignedFaultM := io.StoreAmoMisalignedFaultM
  trap.io.LoadAccessFaultM := io.LoadAccessFaultM
  trap.io.StoreAmoAccessFaultM := io.StoreAmoAccessFaultM
  trap.io.LoadPageFaultM := io.LoadPageFaultM
  trap.io.StoreAmoPageFaultM := io.StoreAmoPageFaultM

  wfi.io.ValidM := ActiveM
  wfi.io.wfiM := decode.io.wfiM
  wfi.io.FlushM := io.FlushM
  wfi.io.TrapM := trap.io.TrapM
  wfi.io.IntPendingM := trap.io.IntPendingM

  val FenceM = InstructionDecode.isFence(io.InstrM)
  val CommitM = ActiveM && !io.StallW
  val RedirectM = trap.io.TrapM || decode.io.RetM ||
    csr.io.CSRWriteM || (FenceM && !decode.io.IllegalInstrFaultM)
  io.RedirectM := CommitM && RedirectM
  io.RedirectPCM := Mux(trap.io.TrapM, csr.io.TrapVectorM,
    Mux(decode.io.RetM, csr.io.EPCM, io.PCM + INSTRUCTION_BYTES.U(XLEN.W)))
  io.WFIStallM := ActiveM && wfi.io.WFIStallM
  io.WFITimeoutM := WFITimeoutM
  io.TrapM := trap.io.TrapM
  io.InterruptM := trap.io.InterruptM
  io.ExceptionM := trap.io.ExceptionM
  io.CauseM := trap.io.CauseM
  io.RetM := decode.io.RetM
  io.wfiM := decode.io.wfiM
  io.IllegalInstrFaultM := decode.io.IllegalInstrFaultM
  io.PrivilegeModeW := mode.io.PrivilegeModeW
  io.NextPrivilegeModeM := mode.io.NextPrivilegeModeM
  io.CSRResultM := csr.io.CSRResultM
  io.IsCSRInstrM := csr.io.IsCSRInstrM
  io.CSRReadM := csr.io.CSRReadM
  io.CSRWriteM := csr.io.CSRWriteM
  io.BigEndianM := csr.io.BigEndianM
  io.MSTATUS_REGW := csr.io.MSTATUS_REGW
  io.MCYCLE_REGW := csr.io.MCYCLE_REGW
  io.MINSTRET_REGW := csr.io.MINSTRET_REGW
}
