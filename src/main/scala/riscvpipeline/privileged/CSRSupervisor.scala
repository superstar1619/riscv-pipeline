package riscvpipeline.privileged

import chisel3._
import chisel3.util.{Cat, MuxLookup, is, switch}

/** 监督CSR的存在性、额外权限、共享视图和已授权的写/陷阱事件。 */
final class CSRSupervisorIO extends Bundle {
  val ValidM = Input(Bool())
  val StallW = Input(Bool())
  val FlushM = Input(Bool())
  val CSRWriteM = Input(Bool())
  val STrapM = Input(Bool())
  val CSRAdrM = Input(UInt(12.W))
  val CSRWriteValM = Input(UInt(32.W))
  val NextEPCM = Input(UInt(32.W))
  val NextCauseM = Input(UInt(6.W))
  val NextXtvalM = Input(UInt(32.W))
  val PrivilegeModeW = Input(UInt(2.W))
  val STATUS_TVM = Input(Bool())
  val ENVCFG_STCE = Input(Bool())
  val MCOUNTEREN_TM = Input(Bool())
  val TIME_REGW = Input(UInt(64.W))
  val SSTATUS_REGW = Input(UInt(32.W))
  val SIP_REGW = Input(UInt(12.W))
  val SIE_REGW = Input(UInt(12.W))
  val SIP_REGW_writeable = Input(UInt(12.W))

  val CSRSExistsM = Output(Bool())
  val CSRSAccessAllowedM = Output(Bool())
  val CSRSReadValM = Output(UInt(32.W))
  val CSRSModifyValM = Output(UInt(32.W))
  val STVEC_REGW = Output(UInt(32.W))
  val SEPC_REGW = Output(UInt(32.W))
  val SCAUSE_REGW = Output(UInt(32.W))
  val STVAL_REGW = Output(UInt(32.W))
  val SSCRATCH_REGW = Output(UInt(32.W))
  val SCOUNTEREN_REGW = Output(UInt(32.W))
  val SATP_REGW = Output(UInt(32.W))
  val SENVCFG_REGW = Output(UInt(32.W))
  val STIMECMP_REGW = Output(UInt(64.W))
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
  require(hpmCounters >= 0 && hpmCounters <= 29, "HPM counter count must be 0..29")
  require(asidBits >= 0 && asidBits <= 9, "RV32 ASID bits must be 0..9")
  require(asidBits == 0 || virtualMemorySupported, "ASID bits require VM support")
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new CSRSupervisorIO)

  private val COUNTER_MASK = ((BigInt(1) << hpmCounters) - 1) << 3 | BigInt(7)
  private val SATP_MASK = BigInt("803fffff", 16) | (((BigInt(1) << asidBits) - 1) << 22)
  val TVec = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val EPC = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val Cause = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val Tval = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val Scratch = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val CounterEnable = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val Satp = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val Env = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val CompareLow = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val CompareHigh = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val Compare = Cat(CompareHigh, CompareLow)
  val MachineModeM = io.PrivilegeModeW === 3.U(2.W)
  val SupervisorModeM = io.PrivilegeModeW === 1.U(2.W)
  val TrapSourceAllowedM = SupervisorModeM || io.PrivilegeModeW === 0.U(2.W)
  val TimerAddressM = io.CSRAdrM === "h14d".U(12.W) || io.CSRAdrM === "h15d".U(12.W)
  val SatpAllowedM = io.CSRAdrM =/= "h180".U(12.W) || MachineModeM || !io.STATUS_TVM
  // 官方Sstc：M不受STCE/TM限制，S读取和写入都要求两者为1。
  val TimerAllowedM = !TimerAddressM || MachineModeM ||
    (io.ENVCFG_STCE && io.MCOUNTEREN_TM)
  val ReadEntries = Seq(
    0x100 -> io.SSTATUS_REGW, 
    0x104 -> io.SIE_REGW.pad(32), 
    0x105 -> TVec,
    0x106 -> CounterEnable, 
    0x10a -> Env, 
    0x140 -> Scratch,
    0x141 -> EPC, 
    0x142 -> Cause, 
    0x143 -> Tval, 
    0x144 -> io.SIP_REGW.pad(32),
    0x180 -> Satp
  ) ++ (if (sstcSupported) Seq(0x14d -> CompareLow, 0x15d -> CompareHigh) else Seq.empty)

  // 存在性不受模式、权限或软件写影响，供CSRAccess判断；权限单独反馈。
  io.CSRSExistsM := supervisorSupported.B && ReadEntries.map { case (address, _) =>
    io.CSRAdrM === address.U(12.W)
  }.reduce(_ || _)
  io.CSRSAccessAllowedM := io.CSRSExistsM && (MachineModeM || SupervisorModeM) &&
    SatpAllowedM && TimerAllowedM
  val ReadValueM = MuxLookup(io.CSRAdrM, 0.U(32.W))(
    ReadEntries.map { case (address, value) => address.U(12.W) -> value })
  val ModifyValueM = Mux(io.CSRAdrM === "h144".U(12.W),
    io.SIP_REGW_writeable.pad(32), ReadValueM)
  io.CSRSReadValM := Mux(io.CSRSAccessAllowedM, ReadValueM, 0.U(32.W))
  io.CSRSModifyValM := Mux(io.CSRSAccessAllowedM, ModifyValueM, 0.U(32.W))

  val UpdateM = supervisorSupported.B && io.ValidM && !io.StallW && !io.FlushM
  val SoftwareWriteM = UpdateM && !io.STrapM && io.CSRWriteM &&
    io.CSRSAccessAllowedM
  val DirectTVec = Cat(io.CSRWriteValM(31, 2), 0.U(2.W))
  val WrittenTVec = if (vectoredInterruptsSupported) {
    Mux(io.CSRWriteValM(0), Cat(io.CSRWriteValM(31, 6), 0.U(5.W), 1.U(1.W)),
      DirectTVec)
  } else {
    DirectTVec
  }

  withClockAndReset(clk, reset) {
    when(UpdateM) {
      // 非法raw STrap占据优先级但保持状态，不能下降到同拍软件写。
      when(io.STrapM) {
        when(TrapSourceAllowedM) {
          EPC := Cat(io.NextEPCM(31, 2), 0.U(2.W))
          Cause := Cat(io.NextCauseM(5), 0.U(26.W), io.NextCauseM(4, 0))
          Tval := io.NextXtvalM
        }
      }.elsewhen(SoftwareWriteM) {
        switch(io.CSRAdrM) {
          is("h105".U) { TVec := WrittenTVec }
          is("h140".U) { Scratch := io.CSRWriteValM }
          is("h141".U) { EPC := Cat(io.CSRWriteValM(31, 2), 0.U(2.W)) }
          is("h142".U) {
            Cause := Cat(io.CSRWriteValM(31), 0.U(26.W), io.CSRWriteValM(4, 0))
          }
          is("h143".U) { Tval := io.CSRWriteValM }
          is("h106".U) { CounterEnable := io.CSRWriteValM & COUNTER_MASK.U(32.W) }
          is("h10a".U) {
            Env := io.CSRWriteValM & (if (virtualMemorySupported) 1 else 0).U(32.W)
          }
          is("h180".U) {
            if (virtualMemorySupported) {
              // Sv32只保存已配置的低ASID位；本项目不实现Bare自定义编码。
              Satp := Mux(io.CSRWriteValM(31), io.CSRWriteValM & SATP_MASK.U(32.W),
                0.U(32.W))
            }
          }
          is("h14d".U) { CompareLow := io.CSRWriteValM }
          is("h15d".U) { CompareHigh := io.CSRWriteValM }
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
  io.WriteSSTATUSM := SoftwareWriteM && io.CSRAdrM === "h100".U(12.W)
  io.WriteSIEM := SoftwareWriteM && io.CSRAdrM === "h104".U(12.W)
  io.WriteSIPM := SoftwareWriteM && io.CSRAdrM === "h144".U(12.W)
}
