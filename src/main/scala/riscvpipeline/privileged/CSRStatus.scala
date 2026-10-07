package riscvpipeline.privileged

import chisel3._
import chisel3.util.{Cat, MuxLookup}

/** RV32 状态寄存器共享视图及已授权的更新事件。 */
final class CSRStatusIO extends Bundle {
  val ValidM = Input(Bool())
  val StallW = Input(Bool())
  val FlushM = Input(Bool())
  val WriteMSTATUSM = Input(Bool())
  val WriteMSTATUSHM = Input(Bool())
  val WriteSSTATUSM = Input(Bool())
  val TrapM = Input(Bool())
  val PrivilegeModeW = Input(UInt(2.W))
  val NextPrivilegeModeM = Input(UInt(2.W))
  val mretM = Input(Bool())
  val sretM = Input(Bool())
  val CSRWriteValM = Input(UInt(32.W))
  val SelHPTW = Input(Bool())

  val MSTATUS_REGW = Output(UInt(32.W))
  val SSTATUS_REGW = Output(UInt(32.W))
  val MSTATUSH_REGW = Output(UInt(32.W))
  val STATUS_MPP = Output(UInt(2.W))
  val STATUS_SPP = Output(Bool())
  val STATUS_TSR = Output(Bool())
  val STATUS_TW = Output(Bool())
  val STATUS_TVM = Output(Bool())
  val STATUS_MIE = Output(Bool())
  val STATUS_SIE = Output(Bool())
  val STATUS_MXR = Output(Bool())
  val STATUS_SUM = Output(Bool())
  val STATUS_MPRV = Output(Bool())
  val STATUS_FS = Output(UInt(2.W))
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

  private val WORD_MASK = BigInt("ffffffff", 16)
  private val MPP_MASK = BigInt("1800", 16)
  private val S_MASK = if (supervisorSupported) BigInt("80122", 16) |
    (if (virtualMemorySupported) BigInt("40000", 16) else BigInt(0)) |
    (if (bigEndianSupported) BigInt("40", 16) else BigInt(0)) else BigInt(0)
  private val M_MASK = BigInt("221888", 16) | S_MASK |
    (if (supervisorSupported) BigInt("500000", 16) else BigInt(0)) |
    (if (bigEndianSupported) BigInt("40", 16) else BigInt(0))
  private val H_MASK = if (bigEndianSupported)
    BigInt(0x20 | (if (supervisorSupported) 0x10 else 0)) else BigInt(0)

  val Status = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val StatusHigh = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val MachineModeM = io.PrivilegeModeW === 3.U(2.W)
  val SupervisorModeM = io.PrivilegeModeW === 1.U(2.W)
  val SupportedModeM = MachineModeM || io.PrivilegeModeW === 0.U(2.W) ||
    (supervisorSupported.B && SupervisorModeM)
  val TrapAllowedM = SupportedModeM && io.NextPrivilegeModeM >= io.PrivilegeModeW &&
    (io.NextPrivilegeModeM === 3.U(2.W) ||
      (supervisorSupported.B && io.NextPrivilegeModeM === 1.U(2.W)))
  val SretAllowedM = supervisorSupported.B &&
    (MachineModeM || (SupervisorModeM && !Status(22)))

  // WARL：非法/未支持的 MPP 写编码只保留旧 MPP，其他可写字段仍更新。
  val WrittenMPP = io.CSRWriteValM(12, 11)
  val MPPAllowedM = WrittenMPP === 0.U(2.W) || WrittenMPP === 3.U(2.W) ||
    (supervisorSupported.B && WrittenMPP === 1.U(2.W))
  val SoftwareMPP = Mux(MPPAllowedM, WrittenMPP, Status(12, 11))
  val SoftwareStatus = (io.CSRWriteValM & (M_MASK & ~MPP_MASK).U(32.W)) |
    Cat(0.U(19.W), SoftwareMPP, 0.U(11.W))

  // 掩码仅替换指定字段，保留另一特权级的中断栈与所有端序状态。
  private def replace(mask: BigInt, bits: UInt): UInt =
    (Status & (WORD_MASK ^ mask).U(32.W)) | bits
  val MachineTrapStatus = replace(BigInt("1888", 16),
    Cat(0.U(19.W), io.PrivilegeModeW, 0.U(3.W), Status(3), 0.U(7.W)))
  val SupervisorTrapStatus = replace(BigInt("122", 16),
    Cat(0.U(23.W), SupervisorModeM, 0.U(2.W), Status(1), 0.U(5.W)))
  val MachineReturnStatus = replace(BigInt("1888", 16),
    Cat(0.U(24.W), 1.U(1.W), 0.U(3.W), Status(7), 0.U(3.W)))
  val SupervisorReturnStatus = replace(BigInt("20122", 16),
    Cat(0.U(26.W), 1.U(1.W), 0.U(3.W), Status(5), 0.U(1.W)))

  withClockAndReset(clk, reset) {
    when(io.ValidM && !io.StallW && !io.FlushM) {
      // 无效事件保留优先级并保持状态，不能落入低优先级软件写入。
      when(io.TrapM) {
        when(TrapAllowedM) {
          Status := Mux(io.NextPrivilegeModeM === 3.U(2.W),
            MachineTrapStatus, SupervisorTrapStatus)
        }
      }.elsewhen(io.mretM) {
        when(MachineModeM) {
          Status := Mux(Status(12, 11) === 3.U(2.W), MachineReturnStatus,
            MachineReturnStatus & "hfffdffff".U(32.W))
        }
      }.elsewhen(io.sretM) {
        when(SretAllowedM) { Status := SupervisorReturnStatus }
      }.elsewhen(io.WriteMSTATUSM) {
        Status := SoftwareStatus
      }.elsewhen(io.WriteMSTATUSHM) {
        StatusHigh := io.CSRWriteValM & H_MASK.U(32.W)
      }.elsewhen(io.WriteSSTATUSM && supervisorSupported.B) {
        Status := replace(S_MASK, io.CSRWriteValM & S_MASK.U(32.W))
      }
    }
  }

  io.MSTATUS_REGW := Status
  io.SSTATUS_REGW := Status & S_MASK.U(32.W)
  io.MSTATUSH_REGW := StatusHigh
  io.STATUS_MPP := Status(12, 11)
  io.STATUS_SPP := Status(8)
  io.STATUS_TSR := Status(22)
  io.STATUS_TW := Status(21)
  io.STATUS_TVM := Status(20)
  io.STATUS_MIE := Status(3)
  io.STATUS_SIE := Status(1)
  io.STATUS_MXR := Status(19)
  io.STATUS_SUM := Status(18)
  io.STATUS_MPRV := Status(17)
  io.STATUS_FS := 0.U(2.W)

  // 只选择数据访问端序；字节交换和真实页表访问由后续模块完成。
  val EffectiveModeM = Mux(virtualMemorySupported.B && io.SelHPTW, 1.U(2.W),
    Mux(MachineModeM && Status(17), Status(12, 11), io.PrivilegeModeW))
  io.BigEndianM := bigEndianSupported.B && MuxLookup(EffectiveModeM, false.B)(Seq(
    0.U(2.W) -> Status(6), 1.U(2.W) -> StatusHigh(4), 3.U(2.W) -> StatusHigh(5)))
}
