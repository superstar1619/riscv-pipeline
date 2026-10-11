package riscvpipeline.privileged

import chisel3._
import chisel3.util.{Cat, MuxLookup}
import riscvpipeline.isa.Architecture._
import PrivilegedConstants.Privilege._
import PrivilegedConstants.StatusField._
import PrivilegedConstants._

/** RV32 状态寄存器共享视图及已授权的更新事件。 */
final class CSRStatusIO extends Bundle {
  val ValidM = Input(Bool())
  val StallW = Input(Bool())
  val FlushM = Input(Bool())
  val WriteMSTATUSM = Input(Bool())
  val WriteMSTATUSHM = Input(Bool())
  val WriteSSTATUSM = Input(Bool())
  val TrapM = Input(Bool())
  val PrivilegeModeW = Input(UInt(MODE_WIDTH.W))
  val NextPrivilegeModeM = Input(UInt(MODE_WIDTH.W))
  val mretM = Input(Bool())
  val sretM = Input(Bool())
  val CSRWriteValM = Input(UInt(XLEN.W))
  val SelHPTW = Input(Bool())

  val MSTATUS_REGW = Output(UInt(XLEN.W))
  val SSTATUS_REGW = Output(UInt(XLEN.W))
  val MSTATUSH_REGW = Output(UInt(XLEN.W))
  val STATUS_MPP = Output(UInt(MODE_WIDTH.W))
  val STATUS_SPP = Output(Bool())
  val STATUS_TSR = Output(Bool())
  val STATUS_TW = Output(Bool())
  val STATUS_TVM = Output(Bool())
  val STATUS_MIE = Output(Bool())
  val STATUS_SIE = Output(Bool())
  val STATUS_MXR = Output(Bool())
  val STATUS_SUM = Output(Bool())
  val STATUS_MPRV = Output(Bool())
  val STATUS_FS = Output(UInt(FS_WIDTH.W))
  val BigEndianM = Output(Bool())
}

/** 教材 §8.2.3.1、图 8.3、§8.4.3：固定 RV32，无浮点/向量状态。
  * 显式时钟、高有效同步复位；U/M 始终支持，S/VM/大端为可选配置。
  */
final class CSRStatus(
  val supervisorSupported: Boolean = true,
  val virtualMemorySupported: Boolean = false,
  val bigEndianSupported: Boolean = false
) extends RawModule {
  require(!virtualMemorySupported || supervisorSupported,
    "virtual memory support requires supervisor support")
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new CSRStatusIO)

  private val S_MASK = if (supervisorSupported) SUPERVISOR_BASE_MASK |
    (if (virtualMemorySupported) SUM_MASK else BigInt(0)) |
    (if (bigEndianSupported) UBE_MASK else BigInt(0)) else BigInt(0)
  private val M_MASK = MACHINE_BASE_MASK | S_MASK |
    (if (supervisorSupported) SUPERVISOR_CONTROL_MASK else BigInt(0)) |
    (if (bigEndianSupported) UBE_MASK else BigInt(0))
  private val H_MASK = if (bigEndianSupported)
    BigInt(MBE_HIGH_MASK |
      (if (supervisorSupported) SBE_HIGH_MASK else 0)) else BigInt(0)

  val Status = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
  val StatusHigh = withClockAndReset(clk, reset) { RegInit(0.U(XLEN.W)) }
  val MachineModeM = io.PrivilegeModeW === MODE_M.U(MODE_WIDTH.W)
  val SupervisorModeM = io.PrivilegeModeW === MODE_S.U(MODE_WIDTH.W)
  val SupportedModeM = MachineModeM || io.PrivilegeModeW === MODE_U.U(MODE_WIDTH.W) ||
    (supervisorSupported.B && SupervisorModeM)
  val TrapAllowedM = SupportedModeM && io.NextPrivilegeModeM >= io.PrivilegeModeW &&
    (io.NextPrivilegeModeM === MODE_M.U(MODE_WIDTH.W) ||
      (supervisorSupported.B && io.NextPrivilegeModeM === MODE_S.U(MODE_WIDTH.W)))
  val SretAllowedM = supervisorSupported.B &&
    (MachineModeM || (SupervisorModeM && !Status(TSR_BIT)))

  // WARL：非法/未支持的 MPP 写编码只保留旧 MPP，其他可写字段仍更新。
  val WrittenMPP = io.CSRWriteValM(MPP_HIGH, MPP_LOW)
  val MPPAllowedM = WrittenMPP === MODE_U.U(MODE_WIDTH.W) ||
    WrittenMPP === MODE_M.U(MODE_WIDTH.W) ||
    (supervisorSupported.B && WrittenMPP === MODE_S.U(MODE_WIDTH.W))
  val SoftwareMPP = Mux(MPPAllowedM, WrittenMPP, Status(MPP_HIGH, MPP_LOW))
  val SoftwareStatus = (io.CSRWriteValM & (M_MASK & ~MPP_MASK).U(XLEN.W)) |
    Cat(0.U(19.W), SoftwareMPP, 0.U(11.W))

  // 掩码仅替换指定字段，保留另一特权级的中断栈与所有端序状态。
  private def replace(mask: BigInt, bits: UInt): UInt =
    (Status & (WORD_MASK ^ mask).U(XLEN.W)) | bits
  val MachineTrapStatus = replace(MACHINE_STACK_MASK,
    Cat(0.U(19.W), io.PrivilegeModeW, 0.U(3.W), Status(MIE_BIT), 0.U(7.W)))
  val SupervisorTrapStatus = replace(SUPERVISOR_STACK_MASK,
    Cat(0.U(23.W), SupervisorModeM, 0.U(2.W), Status(SIE_BIT), 0.U(5.W)))
  val MachineReturnStatus = replace(MACHINE_STACK_MASK,
    Cat(0.U(24.W), 1.U(1.W), 0.U(3.W), Status(MPIE_BIT), 0.U(3.W)))
  val SupervisorReturnStatus = replace(SUPERVISOR_RETURN_MASK,
    Cat(0.U(26.W), 1.U(1.W), 0.U(3.W), Status(SPIE_BIT), 0.U(1.W)))

  withClockAndReset(clk, reset) {
    when(io.ValidM && !io.StallW && !io.FlushM) {
      // 无效事件保留优先级并保持状态，不能落入低优先级软件写入。
      when(io.TrapM) {
        when(TrapAllowedM) {
          Status := Mux(io.NextPrivilegeModeM === MODE_M.U(MODE_WIDTH.W),
            MachineTrapStatus, SupervisorTrapStatus)
        }
      }.elsewhen(io.mretM) {
        when(MachineModeM) {
          Status := Mux(Status(MPP_HIGH, MPP_LOW) === MODE_M.U(MODE_WIDTH.W),
            MachineReturnStatus,
            MachineReturnStatus & MPRV_CLEAR_MASK.U(XLEN.W))
        }
      }.elsewhen(io.sretM) {
        when(SretAllowedM) { Status := SupervisorReturnStatus }
      }.elsewhen(io.WriteMSTATUSM) {
        Status := SoftwareStatus
      }.elsewhen(io.WriteMSTATUSHM) {
        StatusHigh := io.CSRWriteValM & H_MASK.U(XLEN.W)
      }.elsewhen(io.WriteSSTATUSM && supervisorSupported.B) {
        Status := replace(S_MASK, io.CSRWriteValM & S_MASK.U(XLEN.W))
      }
    }
  }

  io.MSTATUS_REGW := Status
  io.SSTATUS_REGW := Status & S_MASK.U(XLEN.W)
  io.MSTATUSH_REGW := StatusHigh
  io.STATUS_MPP := Status(MPP_HIGH, MPP_LOW)
  io.STATUS_SPP := Status(SPP_BIT)
  io.STATUS_TSR := Status(TSR_BIT)
  io.STATUS_TW := Status(TW_BIT)
  io.STATUS_TVM := Status(TVM_BIT)
  io.STATUS_MIE := Status(MIE_BIT)
  io.STATUS_SIE := Status(SIE_BIT)
  io.STATUS_MXR := Status(MXR_BIT)
  io.STATUS_SUM := Status(SUM_BIT)
  io.STATUS_MPRV := Status(MPRV_BIT)
  io.STATUS_FS := 0.U(FS_WIDTH.W)

  // 只选择数据访问端序；字节交换和真实页表访问由后续模块完成。
  val EffectiveModeM = Mux(virtualMemorySupported.B && io.SelHPTW, MODE_S.U(MODE_WIDTH.W),
    Mux(MachineModeM && Status(MPRV_BIT), Status(MPP_HIGH, MPP_LOW), io.PrivilegeModeW))
  io.BigEndianM := bigEndianSupported.B && MuxLookup(EffectiveModeM, false.B)(Seq(
    MODE_U.U(MODE_WIDTH.W) -> Status(UBE_BIT),
    MODE_S.U(MODE_WIDTH.W) -> StatusHigh(SBE_HIGH_BIT),
    MODE_M.U(MODE_WIDTH.W) -> StatusHigh(MBE_HIGH_BIT)
  ))
}
