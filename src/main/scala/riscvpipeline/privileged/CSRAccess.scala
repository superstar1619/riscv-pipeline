package riscvpipeline.privileged

import chisel3._
import chisel3.util.{Cat, MuxLookup}

/** RV32 CSR 指令访问：寄存器模块提供旧值、修改基值及额外权限。 */
final class CSRAccessIO extends Bundle {
  val InstrM = Input(UInt(32.W))
  val ValidM = Input(Bool())
  val PrivilegeModeW = Input(UInt(2.W))
  val SrcAM = Input(UInt(32.W))
  val CSRReadValM = Input(UInt(32.W))
  val CSRModifyValM = Input(UInt(32.W))
  val CSRExistsM = Input(Bool())
  val CSRAccessAllowedM = Input(Bool())

  val IsCSRInstrM = Output(Bool())
  val CSRAdrM = Output(UInt(12.W))
  val CSRSrcM = Output(UInt(32.W))
  val CSRReadM = Output(Bool())
  val CSRWriteM = Output(Bool())
  val CSRWriteValM = Output(UInt(32.W))
  val CSRResultM = Output(UInt(32.W))
  val IllegalCSRAccessM = Output(Bool())
}

/** 无时钟、复位和状态；教材 §8.2.2.1、§8.4.3 的独立访问子模块。
  * 支持 U/S/M，拒绝模式及地址最低特权编码 10；提交门控留给后续集成。
  */
final class CSRAccess extends RawModule {
  val io = IO(new CSRAccessIO)

  val Funct3M = io.InstrM(14, 12)
  val SourceFieldM = io.InstrM(19, 15)
  val DestFieldM = io.InstrM(11, 7)
  val IsRWM = Funct3M === 1.U(3.W) || Funct3M === 5.U(3.W)
  val IsRSM = Funct3M === 2.U(3.W) || Funct3M === 6.U(3.W)
  val IsRCM = Funct3M === 3.U(3.W) || Funct3M === 7.U(3.W)

  // 地址和源值是纯译码；指令有效性只门控实际访问与异常。
  io.IsCSRInstrM := io.InstrM(6, 0) === "h73".U(7.W) && (IsRWM || IsRSM || IsRCM)
  io.CSRAdrM := io.InstrM(31, 20)
  io.CSRSrcM := Mux(io.InstrM(14), Cat(0.U(27.W), SourceFieldM), io.SrcAM)
  val ActiveM = io.ValidM && io.IsCSRInstrM
  val ReadIntentM = !IsRWM || DestFieldM =/= 0.U(5.W)
  // 非零寄存器即使持有零也发起写入，不能以 CSRSrcM == 0 判断。
  val WriteIntentM = IsRWM || SourceFieldM =/= 0.U(5.W)

  val MinimumPrivilegeM = io.CSRAdrM(9, 8)
  val SupportedModeM = io.PrivilegeModeW =/= 2.U(2.W)
  val SupportedAddressM = MinimumPrivilegeM =/= 2.U(2.W)
  val PrivilegeAllowedM = SupportedModeM && SupportedAddressM &&
    io.PrivilegeModeW >= MinimumPrivilegeM
  val ReadOnlyWriteM = io.CSRAdrM(11, 10) === 3.U(2.W) && WriteIntentM
  val AccessLegalM = io.CSRExistsM && io.CSRAccessAllowedM &&
    PrivilegeAllowedM && !ReadOnlyWriteM

  // 权限检查独立于读取意图，覆盖 CSRRW[I] rd=x0 的只写访问。
  io.IllegalCSRAccessM := ActiveM && !AccessLegalM
  io.CSRReadM := ActiveM && AccessLegalM && ReadIntentM
  io.CSRWriteM := ActiveM && AccessLegalM && WriteIntentM

  // mip/sip 的调用方提供内部可写状态，避免把外部中断输入写回。
  val WriteValueM = MuxLookup(Funct3M(1, 0), 0.U(32.W))(Seq(
    1.U(2.W) -> io.CSRSrcM,
    2.U(2.W) -> (io.CSRModifyValM | io.CSRSrcM),
    3.U(2.W) -> (io.CSRModifyValM & ~io.CSRSrcM)
  ))
  io.CSRWriteValM := Mux(io.CSRWriteM, WriteValueM, 0.U(32.W))
  io.CSRResultM := Mux(io.CSRReadM, io.CSRReadValM, 0.U(32.W))
}
