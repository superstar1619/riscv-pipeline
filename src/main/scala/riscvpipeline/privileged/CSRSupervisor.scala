package riscvpipeline.privileged

import chisel3._
import chisel3.util.{Cat, MuxLookup, is, switch}
import riscvpipeline.isa.Architecture._
import CSRAddresses._
import PrivilegedConstants.ExceptionCause._
import PrivilegedConstants.Privilege._
import PrivilegedConstants._

/** 监督CSR的存在性、额外权限、共享视图和已授权的写/陷阱事件。 */
final class CSRSupervisorIO extends Bundle {
  val ValidM = Input(Bool())
  val StallW = Input(Bool())
  val FlushM = Input(Bool())
  val CSRWriteM = Input(Bool())
  val STrapM = Input(Bool())
  val CSRAdrM = Input(UInt(ADDRESS_WIDTH.W))
  val CSRWriteValM = Input(UInt(XLEN.W))
  val NextEPCM = Input(UInt(XLEN.W))
  val NextCauseM = Input(UInt(TRAP_CAUSE_WIDTH.W))
  val NextXtvalM = Input(UInt(XLEN.W))
  val PrivilegeModeW = Input(UInt(MODE_WIDTH.W))
  val STATUS_TVM = Input(Bool())
  val ENVCFG_STCE = Input(Bool())
  val MCOUNTEREN_TM = Input(Bool())
  val TIME_REGW = Input(UInt(Counter.WIDTH.W))
  val SSTATUS_REGW = Input(UInt(XLEN.W))
  val SIP_REGW = Input(UInt(Interrupt.WIDTH.W))
  val SIE_REGW = Input(UInt(Interrupt.WIDTH.W))
  val SIP_REGW_writeable = Input(UInt(Interrupt.WIDTH.W))

  val CSRSExistsM = Output(Bool())
  val CSRSAccessAllowedM = Output(Bool())
  val CSRSReadValM = Output(UInt(XLEN.W))
  val CSRSModifyValM = Output(UInt(XLEN.W))
  val STVEC_REGW = Output(UInt(XLEN.W))
  val SEPC_REGW = Output(UInt(XLEN.W))
  val SCAUSE_REGW = Output(UInt(XLEN.W))
  val STVAL_REGW = Output(UInt(XLEN.W))
  val SSCRATCH_REGW = Output(UInt(XLEN.W))
  val SCOUNTEREN_REGW = Output(UInt(XLEN.W))
  val SATP_REGW = Output(UInt(XLEN.W))
  val SENVCFG_REGW = Output(UInt(XLEN.W))
  val STIMECMP_REGW = Output(UInt(Counter.WIDTH.W))
  val STimerInt = Output(Bool())
  val WriteSSTATUSM = Output(Bool())
  val WriteSIEM = Output(Bool())
  val WriteSIPM = Output(Bool())
}

/** RV32监督CSR，教材§8.2.3–5/§8.4.3。显式clk，高有效同步reset，IALIGN=32。
  * satp仅保存配置，不实现MMU；Sstc的TIME_REGW必须是同域一致的64位值。
  */
final class CSRSupervisor(
  supervisorSupported: Boolean = true,
  virtualMemorySupported: Boolean = false,
  sstcSupported: Boolean = false,
  vectoredInterruptsSupported: Boolean = true,
  hpmCounters: Int = 0,
  asidBits: Int = 0
) extends RawModule {
  require(!virtualMemorySupported || supervisorSupported, "VM requires supervisor support")
  require(!sstcSupported || supervisorSupported, "Sstc requires supervisor support")
  require(hpmCounters >= 0 && hpmCounters <= Counter.HPM_COUNT,
    "HPM counter count must be 0..29")
  require(asidBits >= 0 && asidBits <= SupervisorTranslation.ASID_MAX_BITS,
    "RV32 ASID bits must be 0..9")
  require(asidBits == 0 || virtualMemorySupported, "ASID bits require VM support")
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new CSRSupervisorIO)

  private val COUNTER_MASK =
    ((BigInt(1) << hpmCounters) - 1) << Counter.HPM_FIRST | Counter.BASE_ENABLE_MASK
  private val SATP_MASK = SupervisorTranslation.MODE_PPN_MASK |
    (((BigInt(1) << asidBits) - 1) << SupervisorTranslation.ASID_LOW)
  val TVec = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
  val EPC = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
  val Cause = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
  val Tval = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
  val Scratch = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
  val CounterEnable = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
  val Satp = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
  val Env = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
  val CompareLow = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
  val CompareHigh = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
  val Compare = Cat(CompareHigh, CompareLow)
  val MachineModeM = io.PrivilegeModeW === MODE_M.U(MODE_WIDTH.W)
  val SupervisorModeM = io.PrivilegeModeW === MODE_S.U(MODE_WIDTH.W)
  val TrapSourceAllowedM = SupervisorModeM || io.PrivilegeModeW === MODE_U.U(MODE_WIDTH.W)
  val TimerAddressM = io.CSRAdrM === STIMECMP.U(ADDRESS_WIDTH.W) ||
    io.CSRAdrM === STIMECMPH.U(ADDRESS_WIDTH.W)
  val SatpAllowedM = io.CSRAdrM =/= SATP.U(ADDRESS_WIDTH.W) || MachineModeM || !io.STATUS_TVM
  // 官方Sstc：M不受STCE/TM限制，S读取和写入都要求两者为1。
  val TimerAllowedM = !TimerAddressM || MachineModeM ||
    (io.ENVCFG_STCE && io.MCOUNTEREN_TM)
  val TimerReadEntries = if (sstcSupported) Seq(
    STIMECMP -> CompareLow,
    STIMECMPH -> CompareHigh
  ) else Seq.empty
  val ReadEntries = Seq(
    SSTATUS -> io.SSTATUS_REGW,
    SIE -> io.SIE_REGW.pad(XLEN),
    STVEC -> TVec,
    SCOUNTEREN -> CounterEnable,
    SENVCFG -> Env,
    SSCRATCH -> Scratch,
    SEPC -> EPC,
    SCAUSE -> Cause,
    STVAL -> Tval,
    SIP -> io.SIP_REGW.pad(XLEN),
    SATP -> Satp
  ) ++ TimerReadEntries

  // 存在性不受模式、权限或软件写影响，供CSRAccess判断；权限单独反馈。
  io.CSRSExistsM := supervisorSupported.B && ReadEntries.map { case (address, _) =>
    io.CSRAdrM === address.U(ADDRESS_WIDTH.W)
  }.reduce(_ || _)
  io.CSRSAccessAllowedM := io.CSRSExistsM && (MachineModeM || SupervisorModeM) &&
    SatpAllowedM && TimerAllowedM
  val ReadValueM = MuxLookup(io.CSRAdrM, 0.U(XLEN.W))(
    ReadEntries.map { case (address, value) => address.U(ADDRESS_WIDTH.W) -> value })
  val ModifyValueM = Mux(io.CSRAdrM === SIP.U(ADDRESS_WIDTH.W),
    io.SIP_REGW_writeable.pad(XLEN), ReadValueM)
  io.CSRSReadValM := Mux(io.CSRSAccessAllowedM, ReadValueM, 0.U(XLEN.W))
  io.CSRSModifyValM := Mux(io.CSRSAccessAllowedM, ModifyValueM, 0.U(XLEN.W))

  val UpdateM = supervisorSupported.B && io.ValidM && !io.StallW && !io.FlushM
  val SoftwareWriteM = UpdateM && !io.STrapM && io.CSRWriteM &&
    io.CSRSAccessAllowedM
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
      // 非法raw STrap占据优先级但保持状态，不能下降到同拍软件写。
      when(io.STrapM) {
        when(TrapSourceAllowedM) {
          EPC := Cat(io.NextEPCM(XLEN - 1, TrapVector.ALIGNMENT_BITS),
            0.U(TrapVector.ALIGNMENT_BITS.W))
          Cause := Cat(io.NextCauseM(INTERRUPT_BIT), 0.U(26.W),
            io.NextCauseM(CAUSE_WIDTH - 1, 0))
          Tval := io.NextXtvalM
        }
      }.elsewhen(SoftwareWriteM) {
        switch(io.CSRAdrM) {
          is(STVEC.U(ADDRESS_WIDTH.W)) { TVec := WrittenTVec }
          is(SSCRATCH.U(ADDRESS_WIDTH.W)) { Scratch := io.CSRWriteValM }
          is(SEPC.U(ADDRESS_WIDTH.W)) {
            EPC := Cat(io.CSRWriteValM(XLEN - 1, TrapVector.ALIGNMENT_BITS),
              0.U(TrapVector.ALIGNMENT_BITS.W))
          }
          is(SCAUSE.U(ADDRESS_WIDTH.W)) {
            Cause := Cat(io.CSRWriteValM(XLEN - 1), 0.U(26.W),
              io.CSRWriteValM(CAUSE_WIDTH - 1, 0))
          }
          is(STVAL.U(ADDRESS_WIDTH.W)) { Tval := io.CSRWriteValM }
          is(SCOUNTEREN.U(ADDRESS_WIDTH.W)) {
            CounterEnable := io.CSRWriteValM & COUNTER_MASK.U(XLEN.W)
          }
          is(SENVCFG.U(ADDRESS_WIDTH.W)) {
            Env := io.CSRWriteValM &
              (if (virtualMemorySupported) EnvironmentConfig.FIOM_MASK else 0).U(XLEN.W)
          }
          is(SATP.U(ADDRESS_WIDTH.W)) {
            if (virtualMemorySupported) {
              // Sv32只保存已配置的低ASID位；本项目不实现Bare自定义编码。
              Satp := Mux(io.CSRWriteValM(SupervisorTranslation.MODE_BIT),
                io.CSRWriteValM & SATP_MASK.U(XLEN.W),
                0.U(XLEN.W))
            }
          }
          is(STIMECMP.U(ADDRESS_WIDTH.W)) { CompareLow := io.CSRWriteValM }
          is(STIMECMPH.U(ADDRESS_WIDTH.W)) { CompareHigh := io.CSRWriteValM }
        }
      }
    }
  }

  io.STVEC_REGW := TVec
  io.SEPC_REGW := EPC
  io.SCAUSE_REGW := Cause
  io.STVAL_REGW := Tval
  io.SSCRATCH_REGW := Scratch
  io.SCOUNTEREN_REGW := CounterEnable
  io.SATP_REGW := Satp
  io.SENVCFG_REGW := Env
  io.STIMECMP_REGW := Compare
  // 原始比较不混入STCE或流水门控；CSRInterrupt负责STIP来源选择。
  io.STimerInt := (supervisorSupported && sstcSupported).B && io.TIME_REGW >= Compare
  io.WriteSSTATUSM := SoftwareWriteM && io.CSRAdrM === SSTATUS.U(ADDRESS_WIDTH.W)
  io.WriteSIEM := SoftwareWriteM && io.CSRAdrM === SIE.U(ADDRESS_WIDTH.W)
  io.WriteSIPM := SoftwareWriteM && io.CSRAdrM === SIP.U(ADDRESS_WIDTH.W)
}
