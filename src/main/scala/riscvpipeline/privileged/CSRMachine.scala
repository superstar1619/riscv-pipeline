package riscvpipeline.privileged

import chisel3._
import chisel3.util.{Cat, MuxLookup, is, switch}
import riscvpipeline.isa.Architecture._
import CSRAddresses._
import PrivilegedConstants.ExceptionCause._
import PrivilegedConstants._

/** 机器CSR地址读值与已授权的提交事件；Next*仅用于机器陷阱记录。 */
final class CSRMachineIO extends Bundle {
  val ValidM = Input(Bool())
  val StallW = Input(Bool())
  val FlushM = Input(Bool())
  val CSRWriteM = Input(Bool())
  val MTrapM = Input(Bool())
  val CSRAdrM = Input(UInt(ADDRESS_WIDTH.W))
  val CSRWriteValM = Input(UInt(XLEN.W))
  val NextEPCM = Input(UInt(XLEN.W))
  val NextCauseM = Input(UInt(TRAP_CAUSE_WIDTH.W))
  val NextXtvalM = Input(UInt(XLEN.W))
  val MSTATUS_REGW = Input(UInt(XLEN.W))
  val MSTATUSH_REGW = Input(UInt(XLEN.W))
  val MIP_REGW = Input(UInt(Interrupt.WIDTH.W))
  val MIE_REGW = Input(UInt(Interrupt.WIDTH.W))
  val MIP_REGW_writeable = Input(UInt(Interrupt.WIDTH.W))

  val CSRMReadValM = Output(UInt(XLEN.W))
  val CSRMModifyValM = Output(UInt(XLEN.W))
  val CSRMExistsM = Output(Bool())
  val MTVEC_REGW = Output(UInt(XLEN.W))
  val MEPC_REGW = Output(UInt(XLEN.W))
  val MCAUSE_REGW = Output(UInt(XLEN.W))
  val MTVAL_REGW = Output(UInt(XLEN.W))
  val MSCRATCH_REGW = Output(UInt(XLEN.W))
  val MCOUNTEREN_REGW = Output(UInt(XLEN.W))
  val MCOUNTINHIBIT_REGW = Output(UInt(XLEN.W))
  val MEDELEG_REGW = Output(UInt(DELEGATION_WIDTH.W))
  val MIDELEG_REGW = Output(UInt(Interrupt.WIDTH.W))
  val MENVCFG_REGW = Output(UInt(EnvironmentConfig.WIDTH.W))
  val WriteMSTATUSM = Output(Bool())
  val WriteMSTATUSHM = Output(Bool())
  val WriteMIEM = Output(Bool())
  val WriteMIPM = Output(Bool())
}

object CSRMachine {
  val MISA_BASE: BigInt = BigInt("40100100", 16)
  val MISA_SUPERVISOR_MASK: BigInt = BigInt("40000", 16)
}

/** RV32机器CSR叶模块；教材§8.2.3–5、§8.4.3，PMP与计数器本体留给后续。
  * 显式clk和高有效同步reset；当前IALIGN=32，不支持动态修改misa。
  */
final class CSRMachine(
  supervisorSupported: Boolean = true,
  virtualMemorySupported: Boolean = false,
  sstcSupported: Boolean = false,
  vectoredInterruptsSupported: Boolean = true,
  hpmCounters: Int = 0,
  hartId: BigInt = 0
) extends RawModule {
  require(!virtualMemorySupported || supervisorSupported, "VM requires supervisor support")
  require(!sstcSupported || supervisorSupported, "Sstc requires supervisor support")
  require(hpmCounters >= 0 && hpmCounters <= Counter.HPM_COUNT,
    "HPM counter count must be 0..29")
  require(hartId >= 0 && hartId < (BigInt(1) << XLEN), "hartId must fit unsigned RV32")
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new CSRMachineIO)

  private val COUNTER_MASK =
    ((BigInt(1) << hpmCounters) - 1) << Counter.HPM_FIRST | Counter.BASE_ENABLE_MASK
  private val DELEG_MASK = if (!supervisorSupported) 0
    else if (virtualMemorySupported) VIRTUAL_MEMORY_MASK
    else SUPERVISOR_MASK
  private val MISA_VALUE = CSRMachine.MISA_BASE |
    (if (supervisorSupported) CSRMachine.MISA_SUPERVISOR_MASK else BigInt(0))

  // 每个val对应一份状态，复位值0；S/扩展关闭后其常零状态可由工具优化。
  val TVec = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
  val EPC = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
  val Cause = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
  val Tval = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
  val Scratch = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
  val CounterEnable = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
  val CounterInhibit = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
  val ExceptionDeleg = withClockAndReset(clk, reset) { RegInit(0.U(DELEGATION_WIDTH.W)) }
  val InterruptDeleg = withClockAndReset(clk, reset) { RegInit(0.U(Interrupt.WIDTH.W)) }
  val EnvLow = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
  val EnvHigh = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }

  val DelegationReadEntries = if (supervisorSupported) Seq(
    MEDELEG -> ExceptionDeleg.pad(XLEN),
    MIDELEG -> InterruptDeleg.pad(XLEN)
  ) else Seq.empty

  // 存在性只依赖地址/配置，不依赖CSRWriteM，允许CSRAccess据此进行授权。
  val ReadEntries = Seq(
    MSTATUS -> io.MSTATUS_REGW,
    MISA -> MISA_VALUE.U(XLEN.W),
    MIE -> io.MIE_REGW.pad(XLEN),
    MTVEC -> TVec,
    MCOUNTEREN -> CounterEnable,
    MENVCFG -> EnvLow,
    MSTATUSH -> io.MSTATUSH_REGW,
    MENVCFGH -> EnvHigh,
    MCOUNTINHIBIT -> CounterInhibit,
    MSCRATCH -> Scratch,
    MEPC -> EPC,
    MCAUSE -> Cause,
    MTVAL -> Tval,
    MIP -> io.MIP_REGW.pad(XLEN),
    MVENDORID -> 0.U(XLEN.W),
    MARCHID -> 0.U(XLEN.W),
    MIMPID -> 0.U(XLEN.W),
    MHARTID -> hartId.U(XLEN.W),
    MCONFIGPTR -> 0.U(XLEN.W)
  ) ++ DelegationReadEntries
  io.CSRMExistsM := ReadEntries.map { case (address, _) =>
    io.CSRAdrM === address.U(ADDRESS_WIDTH.W)
  }.reduce(_ || _)
  io.CSRMReadValM := MuxLookup(io.CSRAdrM, 0.U(XLEN.W))(
    ReadEntries.map { case (address, value) => address.U(ADDRESS_WIDTH.W) -> value })
  io.CSRMModifyValM := Mux(io.CSRAdrM === MIP.U(ADDRESS_WIDTH.W),
    io.MIP_REGW_writeable.pad(XLEN), io.CSRMReadValM)

  val UpdateM = io.ValidM && !io.StallW && !io.FlushM
  val SoftwareWriteM = UpdateM && !io.MTrapM && io.CSRWriteM && io.CSRMExistsM
  val DirectTVec = Cat(io.CSRWriteValM(XLEN - 1, TrapVector.ALIGNMENT_BITS),
    0.U(TrapVector.ALIGNMENT_BITS.W))
  val WrittenTVec = if (vectoredInterruptsSupported) {
    Mux(io.CSRWriteValM(0),
      Cat(io.CSRWriteValM(XLEN - 1, TrapVector.VECTOR_ALIGNMENT_BITS),
        0.U(5.W), TrapVector.MODE_VECTORED.U(1.W)),
      DirectTVec)
  } else {
    DirectTVec
  }

  withClockAndReset(clk, reset) {
    when(UpdateM) {
      // 精确陷阱记录优先于同拍软件写；三个字段使用同一事件的值。
      when(io.MTrapM) {
        EPC := Cat(io.NextEPCM(XLEN - 1, TrapVector.ALIGNMENT_BITS),
          0.U(TrapVector.ALIGNMENT_BITS.W))
        Cause := Cat(io.NextCauseM(INTERRUPT_BIT), 0.U(26.W),
          io.NextCauseM(CAUSE_WIDTH - 1, 0))
        Tval := io.NextXtvalM
      }.elsewhen(SoftwareWriteM) {
        switch(io.CSRAdrM) {
          is(MTVEC.U(ADDRESS_WIDTH.W)) { TVec := WrittenTVec }
          is(MEDELEG.U(ADDRESS_WIDTH.W)) {
            ExceptionDeleg := io.CSRWriteValM(DELEGATION_WIDTH - 1, 0) &
              DELEG_MASK.U(DELEGATION_WIDTH.W)
          }
          is(MIDELEG.U(ADDRESS_WIDTH.W)) {
            InterruptDeleg := io.CSRWriteValM(Interrupt.WIDTH - 1, 0) &
              (if (supervisorSupported) Interrupt.SUPERVISOR_MASK else 0).U(Interrupt.WIDTH.W)
          }
          is(MSCRATCH.U(ADDRESS_WIDTH.W)) { Scratch := io.CSRWriteValM }
          is(MEPC.U(ADDRESS_WIDTH.W)) {
            EPC := Cat(io.CSRWriteValM(XLEN - 1, TrapVector.ALIGNMENT_BITS),
              0.U(TrapVector.ALIGNMENT_BITS.W))
          }
          is(MCAUSE.U(ADDRESS_WIDTH.W)) {
            Cause := Cat(io.CSRWriteValM(XLEN - 1), 0.U(26.W),
              io.CSRWriteValM(CAUSE_WIDTH - 1, 0))
          }
          is(MTVAL.U(ADDRESS_WIDTH.W)) { Tval := io.CSRWriteValM }
          is(MCOUNTEREN.U(ADDRESS_WIDTH.W)) {
            CounterEnable := io.CSRWriteValM & COUNTER_MASK.U(XLEN.W)
          }
          is(MCOUNTINHIBIT.U(ADDRESS_WIDTH.W)) {
            CounterInhibit := io.CSRWriteValM & (COUNTER_MASK & ~Counter.TIME_MASK).U(XLEN.W)
          }
          is(MENVCFG.U(ADDRESS_WIDTH.W)) {
            EnvLow := io.CSRWriteValM &
              (if (virtualMemorySupported) EnvironmentConfig.FIOM_MASK else 0).U(XLEN.W)
          }
          is(MENVCFGH.U(ADDRESS_WIDTH.W)) {
            EnvHigh := io.CSRWriteValM &
              (if (sstcSupported) EnvironmentConfig.STCE_HIGH_MASK else BigInt(0)).U(XLEN.W)
          }
        }
      }
    }
  }

  io.MTVEC_REGW := TVec
  io.MEPC_REGW := EPC
  io.MCAUSE_REGW := Cause
  io.MTVAL_REGW := Tval
  io.MSCRATCH_REGW := Scratch
  io.MCOUNTEREN_REGW := CounterEnable
  io.MCOUNTINHIBIT_REGW := CounterInhibit
  io.MEDELEG_REGW := ExceptionDeleg
  io.MIDELEG_REGW := InterruptDeleg
  io.MENVCFG_REGW := Cat(EnvHigh, EnvLow)
  io.WriteMSTATUSM := SoftwareWriteM && io.CSRAdrM === MSTATUS.U(ADDRESS_WIDTH.W)
  io.WriteMSTATUSHM := SoftwareWriteM && io.CSRAdrM === MSTATUSH.U(ADDRESS_WIDTH.W)
  io.WriteMIEM := SoftwareWriteM && io.CSRAdrM === MIE.U(ADDRESS_WIDTH.W)
  io.WriteMIPM := SoftwareWriteM && io.CSRAdrM === MIP.U(ADDRESS_WIDTH.W)
}
