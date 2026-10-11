package riscvpipeline.privileged

import chisel3._
import chisel3.util.{Cat, MuxLookup}
import riscvpipeline.isa.Architecture._
import riscvpipeline.isa.InstructionDecode
import riscvpipeline.isa.InstructionEncoding._
import CSRAddresses._
import PrivilegedConstants.Privilege._

/** RV32 CSR 指令访问：寄存器模块提供旧值、修改基值及额外权限。 */
final class CSRAccessIO extends Bundle {
  val InstrM = Input(UInt(INSTRUCTION_WIDTH.W))
  val ValidM = Input(Bool())
  val PrivilegeModeW = Input(UInt(MODE_WIDTH.W))
  val SrcAM = Input(UInt(XLEN.W))
  val CSRReadValM = Input(UInt(XLEN.W))
  val CSRModifyValM = Input(UInt(XLEN.W))
  val CSRExistsM = Input(Bool())
  val CSRAccessAllowedM = Input(Bool())

  val IsCSRInstrM = Output(Bool())
  val CSRAdrM = Output(UInt(ADDRESS_WIDTH.W))
  val CSRSrcM = Output(UInt(XLEN.W))
  val CSRReadM = Output(Bool())
  val CSRWriteM = Output(Bool())
  val CSRWriteValM = Output(UInt(XLEN.W))
  val CSRResultM = Output(UInt(XLEN.W))
  val IllegalCSRAccessM = Output(Bool())
}

object CSRAccess {
  val CSR_ADDRESS_LOW: Int = 20
  val IMMEDIATE_BIT: Int = 14
  val OPERATION_WIDTH: Int = 2
}

/** 无时钟、复位和状态；教材 §8.2.2.1、§8.4.3 的独立访问子模块。
  * 支持 U/S/M，拒绝模式及地址最低特权编码 10；提交门控留给后续集成。
  */
final class CSRAccess extends RawModule {
  val io = IO(new CSRAccessIO)

  val Funct3M = io.InstrM(14, 12)
  val SourceFieldM = io.InstrM(19, 15)
  val DestFieldM = io.InstrM(11, 7)
  val IsRWM = Funct3M === CSR_RW.U(FUNCT3_WIDTH.W) || Funct3M === CSR_RWI.U(FUNCT3_WIDTH.W)
  val IsRSM = Funct3M === CSR_RS.U(FUNCT3_WIDTH.W) || Funct3M === CSR_RSI.U(FUNCT3_WIDTH.W)
  val IsRCM = Funct3M === CSR_RC.U(FUNCT3_WIDTH.W) || Funct3M === CSR_RCI.U(FUNCT3_WIDTH.W)

  // 地址和源值是纯译码；指令有效性只门控实际访问与异常。
  io.IsCSRInstrM := InstructionDecode.isCSR(io.InstrM)
  io.CSRAdrM := io.InstrM(INSTRUCTION_WIDTH - 1, CSRAccess.CSR_ADDRESS_LOW)
  io.CSRSrcM := Mux(io.InstrM(CSRAccess.IMMEDIATE_BIT),
    Cat(0.U((XLEN - REGISTER_INDEX_WIDTH).W), SourceFieldM), io.SrcAM)
  val ActiveM = io.ValidM && io.IsCSRInstrM
  val ReadIntentM = !IsRWM || DestFieldM =/= 0.U(REGISTER_INDEX_WIDTH.W)
  // 非零寄存器即使持有零也发起写入，不能以 CSRSrcM == 0 判断。
  val WriteIntentM = IsRWM || SourceFieldM =/= 0.U(REGISTER_INDEX_WIDTH.W)

  val MinimumPrivilegeM = io.CSRAdrM(PRIVILEGE_HIGH, PRIVILEGE_LOW)
  val SupportedModeM = io.PrivilegeModeW =/= MODE_RESERVED.U(MODE_WIDTH.W)
  val SupportedAddressM = MinimumPrivilegeM =/= MODE_RESERVED.U(MODE_WIDTH.W)
  val PrivilegeAllowedM = SupportedModeM && SupportedAddressM &&
    io.PrivilegeModeW >= MinimumPrivilegeM
  val ReadOnlyWriteM = io.CSRAdrM(ACCESS_HIGH, ACCESS_LOW) ===
    READ_ONLY.U(ACCESS_WIDTH.W) && WriteIntentM
  val AccessLegalM = io.CSRExistsM && io.CSRAccessAllowedM &&
    PrivilegeAllowedM && !ReadOnlyWriteM

  // 权限检查独立于读取意图，覆盖 CSRRW[I] rd=x0 的只写访问。
  io.IllegalCSRAccessM := ActiveM && !AccessLegalM
  io.CSRReadM := ActiveM && AccessLegalM && ReadIntentM
  io.CSRWriteM := ActiveM && AccessLegalM && WriteIntentM

  // mip/sip 的调用方提供内部可写状态，避免把外部中断输入写回。
  val WriteValueM = MuxLookup(Funct3M(CSRAccess.OPERATION_WIDTH - 1, 0), 0.U(XLEN.W))(Seq(
    CSR_RW.U(CSRAccess.OPERATION_WIDTH.W) -> io.CSRSrcM,
    CSR_RS.U(CSRAccess.OPERATION_WIDTH.W) -> (io.CSRModifyValM | io.CSRSrcM),
    CSR_RC.U(CSRAccess.OPERATION_WIDTH.W) -> (io.CSRModifyValM & ~io.CSRSrcM)
  ))
  io.CSRWriteValM := Mux(io.CSRWriteM, WriteValueM, 0.U(XLEN.W))
  io.CSRResultM := Mux(io.CSRReadM, io.CSRReadValM, 0.U(XLEN.W))
}
