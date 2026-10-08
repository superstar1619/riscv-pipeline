package riscvpipeline.privileged

import chisel3._
import chisel3.util.PriorityMux

/** RV32 M 级异常、中断及委托组合接口。 */
final class TrapIO extends Bundle {
  val reset = Input(Bool())
  val ValidM = Input(Bool())
  val CommittedM = Input(Bool())
  val CommittedF = Input(Bool())
  val wfiM = Input(Bool())
  val wfiW = Input(Bool())
  val STATUS_MIE = Input(Bool())
  val STATUS_SIE = Input(Bool())
  val InstrMisalignedFaultM = Input(Bool())
  val InstrAccessFaultM = Input(Bool())
  val InstrPageFaultM = Input(Bool())
  val IllegalInstrFaultM = Input(Bool())
  val BreakpointFaultM = Input(Bool())
  val EcallFaultM = Input(Bool())
  val LoadMisalignedFaultM = Input(Bool())
  val StoreAmoMisalignedFaultM = Input(Bool())
  val LoadAccessFaultM = Input(Bool())
  val StoreAmoAccessFaultM = Input(Bool())
  val LoadPageFaultM = Input(Bool())
  val StoreAmoPageFaultM = Input(Bool())
  val PrivilegeModeW = Input(UInt(2.W))
  val MIP_REGW = Input(UInt(12.W))
  val MIE_REGW = Input(UInt(12.W))
  val MIDELEG_REGW = Input(UInt(12.W))
  val MEDELEG_REGW = Input(UInt(16.W))

  val TrapM = Output(Bool())
  val InterruptM = Output(Bool())
  val ExceptionM = Output(Bool())
  val IntPendingM = Output(Bool())
  val DelegateM = Output(Bool())
  val CauseM = Output(UInt(5.W))
}

/** 教材 §8.2.5、CVW trap.sv；纯组合，reset 高电平抑制输出，无状态。 */
final class Trap(val supervisorSupported: Boolean = true) extends RawModule {
  val io = IO(new TrapIO)

  val MachineModeM = io.PrivilegeModeW === 3.U(2.W)
  val SupervisorModeM = io.PrivilegeModeW === 1.U(2.W)
  val UserModeM = io.PrivilegeModeW === 0.U(2.W)
  val SupportedModeM = MachineModeM || UserModeM ||
    (supervisorSupported.B && SupervisorModeM)
  val ActiveM = io.ValidM && !io.reset && SupportedModeM && !io.CommittedF

  val SupportedInts = (if (supervisorSupported) 0xaaa else 0x888).U(12.W)
  val PendingIntsM = io.MIP_REGW & io.MIE_REGW & SupportedInts
  // 关闭 S 时忽略输入委托位，使机器中断不被无效的 S 目标吞掉。
  val IntDelegationM = if (supervisorSupported) io.MIDELEG_REGW else 0.U(12.W)
  val MIntGlobalEnM = !MachineModeM || io.STATUS_MIE
  val SIntGlobalEnM = supervisorSupported.B &&
    (UserModeM || (SupervisorModeM && io.STATUS_SIE))
  val MachineIntsM = Mux(MIntGlobalEnM, PendingIntsM & ~IntDelegationM, 0.U(12.W))
  val SupervisorIntsM = Mux(SIntGlobalEnM, PendingIntsM & IntDelegationM, 0.U(12.W))
  val EnabledIntsM = MachineIntsM | SupervisorIntsM

  io.IntPendingM := PendingIntsM.orR && !io.reset
  io.InterruptM := ActiveM && !io.CommittedM && (!io.wfiM || io.wfiW) &&
    EnabledIntsM.orR

  val ExceptionSourcesM = Seq(
    io.InstrPageFaultM -> 12.U(5.W),
    io.InstrAccessFaultM -> 1.U(5.W),
    io.IllegalInstrFaultM -> 2.U(5.W),
    io.InstrMisalignedFaultM -> 0.U(5.W),
    io.BreakpointFaultM -> 3.U(5.W),
    io.EcallFaultM -> (8.U(5.W) + io.PrivilegeModeW),
    io.StoreAmoMisalignedFaultM -> 6.U(5.W),
    io.LoadMisalignedFaultM -> 4.U(5.W),
    io.StoreAmoPageFaultM -> 15.U(5.W),
    io.LoadPageFaultM -> 13.U(5.W),
    io.StoreAmoAccessFaultM -> 7.U(5.W),
    io.LoadAccessFaultM -> 5.U(5.W)
  )
  io.ExceptionM := ActiveM && ExceptionSourcesM.map(_._1).reduce(_ || _)
  io.TrapM := io.InterruptM || io.ExceptionM

  // 先比较目标权限，再比较各组来源；机器目标始终优先于监督目标。
  val interruptPriority = Seq(11, 3, 7, 9, 1, 5)
  val InterruptSourcesM = interruptPriority.map(n => MachineIntsM(n) -> n.U(5.W)) ++
    interruptPriority.map(n => SupervisorIntsM(n) -> n.U(5.W))
  val InterruptCauseM = PriorityMux(InterruptSourcesM :+ (true.B -> 0.U(5.W)))
  val ExceptionCauseM = PriorityMux(ExceptionSourcesM :+ (true.B -> 0.U(5.W)))
  io.CauseM := Mux(io.TrapM, Mux(io.InterruptM, InterruptCauseM, ExceptionCauseM),
    0.U(5.W))
  val DelegatedM = Mux(io.InterruptM, IntDelegationM(io.CauseM(3, 0)),
    io.MEDELEG_REGW(io.CauseM(3, 0)))
  io.DelegateM := io.TrapM && supervisorSupported.B &&
    (UserModeM || SupervisorModeM) && DelegatedM
}
