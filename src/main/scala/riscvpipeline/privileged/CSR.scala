package riscvpipeline.privileged

import chisel3._
import chisel3.util.Cat
import riscvpipeline.isa.Architecture._
import PrivilegedConstants.ExceptionCause._
import PrivilegedConstants.Privilege._
import PrivilegedConstants._

/** M级CSR事务与精确陷阱上下文；模式由外部PrivMode保存。 */
final class CSRIO extends Bundle {
  val InstrM = Input(UInt(INSTRUCTION_WIDTH.W))
  val PCM = Input(UInt(XLEN.W))
  val IEUAdrM = Input(UInt(XLEN.W))
  val SrcAM = Input(UInt(XLEN.W))
  val PrivilegeModeW = Input(UInt(MODE_WIDTH.W))
  val NextPrivilegeModeM = Input(UInt(MODE_WIDTH.W))
  val CauseM = Input(UInt(CAUSE_WIDTH.W))
  val HPMEventsM = Input(UInt(Counter.EVENT_COUNT.W))
  val TIME_REGW = Input(UInt(Counter.WIDTH.W))
  val ValidM = Input(Bool())
  val StallW = Input(Bool())
  val FlushM = Input(Bool())
  val TrapM = Input(Bool())
  val InterruptM = Input(Bool())
  val mretM = Input(Bool())
  val sretM = Input(Bool())
  val InstrRetiredM = Input(Bool())
  val MExtInt = Input(Bool())
  val SExtInt = Input(Bool())
  val MTimerInt = Input(Bool())
  val MSwInt = Input(Bool())

  val IsCSRInstrM = Output(Bool())
  val CSRReadM = Output(Bool())
  val CSRWriteM = Output(Bool())
  val IllegalCSRAccessM = Output(Bool())
  val STATUS_SPP = Output(Bool())
  val STATUS_TSR = Output(Bool())
  val STATUS_TVM = Output(Bool())
  val STATUS_TW = Output(Bool())
  val STATUS_MIE = Output(Bool())
  val STATUS_SIE = Output(Bool())
  val BigEndianM = Output(Bool())
  val CSRResultM = Output(UInt(XLEN.W))
  val TrapVectorM = Output(UInt(XLEN.W))
  val EPCM = Output(UInt(XLEN.W))
  val STATUS_MPP = Output(UInt(MODE_WIDTH.W))
  val MIP_REGW = Output(UInt(Interrupt.WIDTH.W))
  val MIE_REGW = Output(UInt(Interrupt.WIDTH.W))
  val MIDELEG_REGW = Output(UInt(Interrupt.WIDTH.W))
  val MEDELEG_REGW = Output(UInt(DELEGATION_WIDTH.W))
  val MSTATUS_REGW = Output(UInt(XLEN.W))
  val MCYCLE_REGW = Output(UInt(Counter.WIDTH.W))
  val MINSTRET_REGW = Output(UInt(Counter.WIDTH.W))
}

/** 六个CSR叶模块的RV32汇总；显式clk、高有效同步reset，无额外状态副本。 */
final class CSR(config: PrivilegedConfig = PrivilegedConfig()) extends RawModule {
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new CSRIO)

  val access = Module(new CSRAccess)
  val status = Module(new CSRStatus(config.supervisorSupported,
    bigEndianSupported = config.bigEndianSupported))
  val interrupt = Module(new CSRInterrupt(config.supervisorSupported, config.sstcSupported))
  val machine = Module(new CSRMachine(config.supervisorSupported,
    sstcSupported = config.sstcSupported,
    vectoredInterruptsSupported = config.vectoredInterruptsSupported,
    hpmCounters = config.hpmCounters, hartId = config.hartId))
  val supervisor = Module(new CSRSupervisor(config.supervisorSupported,
    sstcSupported = config.sstcSupported,
    vectoredInterruptsSupported = config.vectoredInterruptsSupported,
    hpmCounters = config.hpmCounters))
  val counters = Module(new CSRCounters(config.supervisorSupported, config.hpmCounters))

  for ((leafClock, leafReset) <- Seq((status.clk, status.reset),
       (interrupt.clk, interrupt.reset), (machine.clk, machine.reset),
       (supervisor.clk, supervisor.reset), (counters.clk, counters.reset))) {
    leafClock := clk
    leafReset := reset
  }
  for (port <- Seq(status.io.ValidM, interrupt.io.ValidM, machine.io.ValidM,
       supervisor.io.ValidM, counters.io.ValidM)) {
    port := io.ValidM
  }
  for (port <- Seq(status.io.StallW, interrupt.io.StallW, machine.io.StallW,
       supervisor.io.StallW, counters.io.StallW)) {
    port := io.StallW
  }
  for (port <- Seq(status.io.FlushM, interrupt.io.FlushM, machine.io.FlushM,
       supervisor.io.FlushM, counters.io.FlushM)) {
    port := io.FlushM
  }
  for (port <- Seq(machine.io.CSRAdrM, supervisor.io.CSRAdrM, counters.io.CSRAdrM)) {
    port := access.io.CSRAdrM
  }
  for (port <- Seq(status.io.CSRWriteValM, interrupt.io.CSRWriteValM,
       machine.io.CSRWriteValM, supervisor.io.CSRWriteValM, counters.io.CSRWriteValM)) {
    port := access.io.CSRWriteValM
  }

  access.io.InstrM := io.InstrM
  access.io.ValidM := io.ValidM
  access.io.PrivilegeModeW := io.PrivilegeModeW
  access.io.SrcAM := io.SrcAM
  // 地址存在性和额外权限独立于写/Trap：不存在授权→写→权限的组合反馈。
  access.io.CSRExistsM := machine.io.CSRMExistsM || supervisor.io.CSRSExistsM ||
    counters.io.CSRCExistsM
  access.io.CSRAccessAllowedM := Mux(supervisor.io.CSRSExistsM,
    supervisor.io.CSRSAccessAllowedM,
    Mux(counters.io.CSRCExistsM, counters.io.CSRCAccessAllowedM, true.B))
  access.io.CSRReadValM := Mux(supervisor.io.CSRSExistsM, supervisor.io.CSRSReadValM,
    Mux(counters.io.CSRCExistsM, counters.io.CSRCReadValM, machine.io.CSRMReadValM))
  access.io.CSRModifyValM := Mux(supervisor.io.CSRSExistsM, supervisor.io.CSRSModifyValM,
    Mux(counters.io.CSRCExistsM, counters.io.CSRCModifyValM, machine.io.CSRMModifyValM))

  // 全局取消避免S陷阱仍写机器组，或M陷阱仍写监督组。叶模块保留提交/复位门控。
  val SoftwareWriteM = access.io.CSRWriteM && !io.TrapM
  for (port <- Seq(machine.io.CSRWriteM, supervisor.io.CSRWriteM, counters.io.CSRWriteM)) {
    port := SoftwareWriteM
  }
  machine.io.MTrapM := io.TrapM && io.NextPrivilegeModeM === MODE_M.U(MODE_WIDTH.W)
  supervisor.io.STrapM := io.TrapM && io.NextPrivilegeModeM === MODE_S.U(MODE_WIDTH.W)
  val NextCauseM = Cat(io.InterruptM, io.CauseM)
  val ZeroTvalM = io.InterruptM || io.CauseM === ECALL_U.U(CAUSE_WIDTH.W) ||
    io.CauseM === ECALL_S.U(CAUSE_WIDTH.W) || io.CauseM === ECALL_M.U(CAUSE_WIDTH.W)
  val PCTvalM = io.CauseM === INSTRUCTION_ACCESS.U(CAUSE_WIDTH.W) ||
    io.CauseM === BREAKPOINT.U(CAUSE_WIDTH.W) ||
    io.CauseM === INSTRUCTION_PAGE.U(CAUSE_WIDTH.W)
  val NextXtvalM = Mux(ZeroTvalM, 0.U(XLEN.W),
    Mux(io.CauseM === ILLEGAL_INSTRUCTION.U(CAUSE_WIDTH.W),
      io.InstrM, Mux(PCTvalM, io.PCM, io.IEUAdrM)))
  for (port <- Seq(machine.io.NextEPCM, supervisor.io.NextEPCM)) {
    port := io.PCM
  }
  for (port <- Seq(machine.io.NextCauseM, supervisor.io.NextCauseM)) {
    port := NextCauseM
  }
  for (port <- Seq(machine.io.NextXtvalM, supervisor.io.NextXtvalM)) {
    port := NextXtvalM
  }

  machine.io.MSTATUS_REGW := status.io.MSTATUS_REGW
  machine.io.MSTATUSH_REGW := status.io.MSTATUSH_REGW
  machine.io.MIP_REGW := interrupt.io.MIP_REGW
  machine.io.MIE_REGW := interrupt.io.MIE_REGW
  machine.io.MIP_REGW_writeable := interrupt.io.MIP_REGW_writeable
  supervisor.io.PrivilegeModeW := io.PrivilegeModeW
  supervisor.io.STATUS_TVM := status.io.STATUS_TVM
  supervisor.io.ENVCFG_STCE := machine.io.MENVCFG_REGW(EnvironmentConfig.STCE_BIT)
  supervisor.io.MCOUNTEREN_TM := machine.io.MCOUNTEREN_REGW(Counter.TIME)
  supervisor.io.TIME_REGW := io.TIME_REGW
  supervisor.io.SSTATUS_REGW := status.io.SSTATUS_REGW
  supervisor.io.SIP_REGW := interrupt.io.SIP_REGW
  supervisor.io.SIE_REGW := interrupt.io.SIE_REGW
  supervisor.io.SIP_REGW_writeable := interrupt.io.SIP_REGW_writeable

  status.io.WriteMSTATUSM := machine.io.WriteMSTATUSM
  status.io.WriteMSTATUSHM := machine.io.WriteMSTATUSHM
  status.io.WriteSSTATUSM := supervisor.io.WriteSSTATUSM
  status.io.TrapM := io.TrapM
  status.io.PrivilegeModeW := io.PrivilegeModeW
  status.io.NextPrivilegeModeM := io.NextPrivilegeModeM
  status.io.mretM := io.mretM
  status.io.sretM := io.sretM
  status.io.SelHPTW := false.B
  interrupt.io.WriteMIEM := machine.io.WriteMIEM
  interrupt.io.WriteMIPM := machine.io.WriteMIPM
  interrupt.io.WriteSIEM := supervisor.io.WriteSIEM
  interrupt.io.WriteSIPM := supervisor.io.WriteSIPM
  interrupt.io.MIDELEG_REGW := machine.io.MIDELEG_REGW
  interrupt.io.MExtInt := io.MExtInt
  interrupt.io.SExtInt := io.SExtInt
  interrupt.io.MTimerInt := io.MTimerInt
  interrupt.io.STimerInt := supervisor.io.STimerInt
  interrupt.io.MSwInt := io.MSwInt
  interrupt.io.ENVCFG_STCE := machine.io.MENVCFG_REGW(EnvironmentConfig.STCE_BIT)

  counters.io.TrapM := io.TrapM
  counters.io.PrivilegeModeW := io.PrivilegeModeW
  counters.io.InstrRetiredM := io.InstrRetiredM
  counters.io.HPMEventsM := io.HPMEventsM
  counters.io.MCOUNTINHIBIT_REGW := machine.io.MCOUNTINHIBIT_REGW
  counters.io.MCOUNTEREN_REGW := machine.io.MCOUNTEREN_REGW
  counters.io.SCOUNTEREN_REGW := supervisor.io.SCOUNTEREN_REGW
  counters.io.TIME_REGW := io.TIME_REGW

  val TVecM = Mux(io.NextPrivilegeModeM === MODE_M.U(MODE_WIDTH.W),
    machine.io.MTVEC_REGW, supervisor.io.STVEC_REGW)
  val VectorBaseM = Cat(TVecM(XLEN - 1, TrapVector.ALIGNMENT_BITS),
    0.U(TrapVector.ALIGNMENT_BITS.W))
  val VectorOffsetM = Cat(io.CauseM, 0.U(TrapVector.ALIGNMENT_BITS.W)).pad(XLEN)
  val UseVectorM = config.vectoredInterruptsSupported.B && io.InterruptM &&
    TVecM(TrapVector.MODE_WIDTH - 1, 0) ===
      TrapVector.MODE_VECTORED.U(TrapVector.MODE_WIDTH.W)
  // 固定RV32按字宽回绕；异常始终base，只有mode01中断加入4*cause。
  io.TrapVectorM := Mux(UseVectorM, VectorBaseM + VectorOffsetM, VectorBaseM)
  io.EPCM := Mux(io.mretM, machine.io.MEPC_REGW, supervisor.io.SEPC_REGW)
  io.IsCSRInstrM := access.io.IsCSRInstrM
  io.CSRReadM := access.io.CSRReadM
  io.CSRWriteM := access.io.CSRWriteM
  io.IllegalCSRAccessM := access.io.IllegalCSRAccessM
  io.CSRResultM := access.io.CSRResultM
  io.STATUS_MPP := status.io.STATUS_MPP
  io.STATUS_SPP := status.io.STATUS_SPP
  io.STATUS_TSR := status.io.STATUS_TSR
  io.STATUS_TVM := status.io.STATUS_TVM
  io.STATUS_TW := status.io.STATUS_TW
  io.STATUS_MIE := status.io.STATUS_MIE
  io.STATUS_SIE := status.io.STATUS_SIE
  io.BigEndianM := status.io.BigEndianM
  io.MIP_REGW := interrupt.io.MIP_REGW
  io.MIE_REGW := interrupt.io.MIE_REGW
  io.MIDELEG_REGW := machine.io.MIDELEG_REGW
  io.MEDELEG_REGW := machine.io.MEDELEG_REGW
  io.MSTATUS_REGW := status.io.MSTATUS_REGW
  io.MCYCLE_REGW := counters.io.MCYCLE_REGW
  io.MINSTRET_REGW := counters.io.MINSTRET_REGW
}
