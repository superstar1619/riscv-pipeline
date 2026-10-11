package riscvpipeline.privileged

import chisel3._
import chisel3.util.PriorityMux
import PrivilegedConstants.ExceptionCause._
import PrivilegedConstants.Privilege._
import PrivilegedConstants._

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
  val PrivilegeModeW = Input(UInt(MODE_WIDTH.W))
  val MIP_REGW = Input(UInt(Interrupt.WIDTH.W))
  val MIE_REGW = Input(UInt(Interrupt.WIDTH.W))
  val MIDELEG_REGW = Input(UInt(Interrupt.WIDTH.W))
  val MEDELEG_REGW = Input(UInt(DELEGATION_WIDTH.W))

  val TrapM = Output(Bool())
  val InterruptM = Output(Bool())
  val ExceptionM = Output(Bool())
  val IntPendingM = Output(Bool())
  val DelegateM = Output(Bool())
  val CauseM = Output(UInt(CAUSE_WIDTH.W))
}

/** 教材 §8.2.5、CVW trap.sv；纯组合，无状态。 */
final class Trap(val supervisorSupported: Boolean = true) extends RawModule {
  val io = IO(new TrapIO)

  val MachineModeM = io.PrivilegeModeW === MODE_M.U(MODE_WIDTH.W)
  val SupervisorModeM = io.PrivilegeModeW === MODE_S.U(MODE_WIDTH.W)
  val UserModeM = io.PrivilegeModeW === MODE_U.U(MODE_WIDTH.W)
  val SupportedModeM = MachineModeM || UserModeM ||
    (supervisorSupported.B && SupervisorModeM)
  val ActiveM = io.ValidM && SupportedModeM && !io.CommittedF

  val SupportedInts =
    (if (supervisorSupported) Interrupt.SUPPORTED_MASK else Interrupt.MACHINE_MASK)
      .U(Interrupt.WIDTH.W)
  val PendingIntsM = io.MIP_REGW & io.MIE_REGW & SupportedInts
  // 关闭 S 时忽略输入委托位，使机器中断不被无效的 S 目标吞掉。
  val IntDelegationM = if (supervisorSupported) io.MIDELEG_REGW else 0.U(Interrupt.WIDTH.W)
  val MIntGlobalEnM = !MachineModeM || io.STATUS_MIE
  val SIntGlobalEnM = supervisorSupported.B &&
    (UserModeM || (SupervisorModeM && io.STATUS_SIE))
  val MachineIntsM = Mux(MIntGlobalEnM, PendingIntsM & ~IntDelegationM, 0.U(Interrupt.WIDTH.W))
  val SupervisorIntsM =
    Mux(SIntGlobalEnM, PendingIntsM & IntDelegationM, 0.U(Interrupt.WIDTH.W))
  val EnabledIntsM = MachineIntsM | SupervisorIntsM

  io.IntPendingM := PendingIntsM.orR
  io.InterruptM := ActiveM && !io.CommittedM && (!io.wfiM || io.wfiW) &&
    EnabledIntsM.orR

  val ExceptionSourcesM = Seq(
    io.InstrPageFaultM -> INSTRUCTION_PAGE.U(CAUSE_WIDTH.W),
    io.InstrAccessFaultM -> INSTRUCTION_ACCESS.U(CAUSE_WIDTH.W),
    io.IllegalInstrFaultM -> ILLEGAL_INSTRUCTION.U(CAUSE_WIDTH.W),
    io.InstrMisalignedFaultM -> INSTRUCTION_MISALIGNED.U(CAUSE_WIDTH.W),
    io.BreakpointFaultM -> BREAKPOINT.U(CAUSE_WIDTH.W),
    io.EcallFaultM -> (ECALL_U.U(CAUSE_WIDTH.W) + io.PrivilegeModeW),
    io.StoreAmoMisalignedFaultM -> STORE_AMO_MISALIGNED.U(CAUSE_WIDTH.W),
    io.LoadMisalignedFaultM -> LOAD_MISALIGNED.U(CAUSE_WIDTH.W),
    io.StoreAmoPageFaultM -> STORE_AMO_PAGE.U(CAUSE_WIDTH.W),
    io.LoadPageFaultM -> LOAD_PAGE.U(CAUSE_WIDTH.W),
    io.StoreAmoAccessFaultM -> STORE_AMO_ACCESS.U(CAUSE_WIDTH.W),
    io.LoadAccessFaultM -> LOAD_ACCESS.U(CAUSE_WIDTH.W)
  )
  io.ExceptionM := ActiveM && ExceptionSourcesM.map(_._1).reduce(_ || _)
  io.TrapM := io.InterruptM || io.ExceptionM

  // 先比较目标权限，再比较各组来源；机器目标始终优先于监督目标。
  val InterruptSourcesM = Interrupt.PRIORITY.map(n => MachineIntsM(n) -> n.U(CAUSE_WIDTH.W)) ++
    Interrupt.PRIORITY.map(n => SupervisorIntsM(n) -> n.U(CAUSE_WIDTH.W))
  val InterruptCauseM = PriorityMux(InterruptSourcesM :+ (true.B -> 0.U(CAUSE_WIDTH.W)))
  val ExceptionCauseM = PriorityMux(ExceptionSourcesM :+ (true.B -> 0.U(CAUSE_WIDTH.W)))
  io.CauseM := Mux(io.TrapM, Mux(io.InterruptM, InterruptCauseM, ExceptionCauseM),
    0.U(CAUSE_WIDTH.W))
  val DelegatedM = Mux(io.InterruptM, IntDelegationM(io.CauseM(DELEGATION_INDEX_WIDTH - 1, 0)),
    io.MEDELEG_REGW(io.CauseM(DELEGATION_INDEX_WIDTH - 1, 0)))
  io.DelegateM := io.TrapM && supervisorSupported.B &&
    (UserModeM || SupervisorModeM) && DelegatedM
}
