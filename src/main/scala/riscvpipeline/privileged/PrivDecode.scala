package riscvpipeline.privileged

import chisel3._
import riscvpipeline.isa.Architecture._
import riscvpipeline.isa.InstructionDecode
import riscvpipeline.isa.InstructionEncoding._
import PrivilegedConstants.Privilege._

/** RV32 特权指令及非法指令汇总接口；WFI 计数由后续等待模块提供。 */
final class PrivDecodeIO extends Bundle {
  val InstrM = Input(UInt(INSTRUCTION_WIDTH.W))
  val ValidM = Input(Bool())
  val PrivilegeModeW = Input(UInt(MODE_WIDTH.W))
  val STATUS_TSR = Input(Bool())
  val STATUS_TVM = Input(Bool())
  val STATUS_TW = Input(Bool())
  val WFITimeoutM = Input(Bool())
  val IllegalIEUInstrM = Input(Bool())
  val IllegalCSRAccessM = Input(Bool())

  val EcallFaultM = Output(Bool())
  val BreakpointFaultM = Output(Bool())
  val sretM = Output(Bool())
  val mretM = Output(Bool())
  val RetM = Output(Bool())
  val wfiM = Output(Bool())
  val sfencevmaM = Output(Bool())
  val IllegalPrivilegedInstrM = Output(Bool())
  val IllegalInstrFaultM = Output(Bool())
}

/** 教材 §8.2.2、§8.4.2；纯组合，无时钟、复位或状态，不改变 CPU。
  * U/M 始终支持，S 可裁剪；虚拟内存仅提供可选译码，不实现 MMU/TLB。
  */
final class PrivDecode(
  val supervisorSupported: Boolean = true,
  val virtualMemorySupported: Boolean = false
) extends RawModule {
  require(!virtualMemorySupported || supervisorSupported,
    "virtual memory decoding requires supervisor support")
  val io = IO(new PrivDecodeIO)

  val SystemM = io.InstrM(6, 0) === OP_SYSTEM.U(OPCODE_WIDTH.W)
  val Funct3M = io.InstrM(14, 12)
  val CSRInstrM = InstructionDecode.isCSR(io.InstrM)
  val MachineModeM = io.PrivilegeModeW === MODE_M.U(MODE_WIDTH.W)
  val SupervisorModeM = io.PrivilegeModeW === MODE_S.U(MODE_WIDTH.W)
  val UserModeM = io.PrivilegeModeW === MODE_U.U(MODE_WIDTH.W)
  val SupportedModeM = MachineModeM || UserModeM ||
    (supervisorSupported.B && SupervisorModeM)

  // 全字比较同时检查 opcode、funct3、rd、rs1，避免接受保留字段。
  val EcallM = io.InstrM === INSTR_ECALL.U(INSTRUCTION_WIDTH.W)
  val EbreakM = io.InstrM === INSTR_EBREAK.U(INSTRUCTION_WIDTH.W)
  val MretM = io.InstrM === INSTR_MRET.U(INSTRUCTION_WIDTH.W) && MachineModeM
  val SretM = io.InstrM === INSTR_SRET.U(INSTRUCTION_WIDTH.W) && supervisorSupported.B &&
    (MachineModeM || (SupervisorModeM && !io.STATUS_TSR))
  val WFIM = io.InstrM === INSTR_WFI.U(INSTRUCTION_WIDTH.W)

  // SFENCE.VMA 只允许 rs1/rs2 变化；默认配置禁用其执行请求。
  val SFenceEncodingM = SystemM && Funct3M === 0.U(FUNCT3_WIDTH.W) &&
    io.InstrM(11, 7) === 0.U(REGISTER_INDEX_WIDTH.W) &&
    io.InstrM(31, 25) === FUNCT7_SFENCE_VMA.U(FUNCT7_WIDTH.W)
  val SFenceM = SFenceEncodingM && virtualMemorySupported.B &&
    (MachineModeM || (SupervisorModeM && !io.STATUS_TVM))
  val KnownPrivilegedM = EcallM || EbreakM || MretM || SretM || WFIM || SFenceM

  // CSR 存在性及地址权限由 CSRAccess 判断；保留 funct3=100 在这里非法。
  io.IllegalPrivilegedInstrM := io.ValidM && SystemM &&
    (!SupportedModeM || (!CSRInstrM && !KnownPrivilegedM))
  val WFITimeoutFaultM = WFIM && SupportedModeM && !MachineModeM &&
    io.WFITimeoutM && (io.STATUS_TW || (supervisorSupported.B && UserModeM))
  io.IllegalInstrFaultM := io.ValidM && (io.IllegalIEUInstrM || io.IllegalCSRAccessM ||
    io.IllegalPrivilegedInstrM || WFITimeoutFaultM)

  val ExecuteM = io.ValidM && SupportedModeM && !io.IllegalInstrFaultM
  io.EcallFaultM := ExecuteM && EcallM
  io.BreakpointFaultM := ExecuteM && EbreakM
  io.mretM := ExecuteM && MretM
  io.sretM := ExecuteM && SretM
  io.RetM := io.mretM || io.sretM
  io.sfencevmaM := ExecuteM && SFenceM
  // 超时时保留识别请求，避免请求撤销使后续等待计数器重启、丢失超时。
  // 集成时 IllegalInstrFaultM 必须优先于 WFI 停顿。
  io.wfiM := io.ValidM && SupportedModeM && WFIM &&
    !io.IllegalIEUInstrM && !io.IllegalCSRAccessM
}
