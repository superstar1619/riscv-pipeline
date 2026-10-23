package riscvpipeline.hazard

import chisel3._
import riscvpipeline.control.ControlEncoding._
import riscvpipeline.isa.Architecture._

/** 源寄存器使用、各级有效写入及访存控制；前递编码 00=寄存器、01=W、10=M。 */
final class HazardIO extends Bundle {
  val Rs1D = Input(UInt(REGISTER_INDEX_WIDTH.W))
  val Rs2D = Input(UInt(REGISTER_INDEX_WIDTH.W))
  val UsesRs1D = Input(Bool())
  val UsesRs2D = Input(Bool())
  val ValidD = Input(Bool())
  val MemRWD = Input(UInt(MEM_WIDTH.W))
  val Rs1E = Input(UInt(REGISTER_INDEX_WIDTH.W))
  val Rs2E = Input(UInt(REGISTER_INDEX_WIDTH.W))
  val RdE = Input(UInt(REGISTER_INDEX_WIDTH.W))
  val ValidE = Input(Bool())
  val MemRWE = Input(UInt(MEM_WIDTH.W))
  val LateResultE = Input(Bool())
  val RdM = Input(UInt(REGISTER_INDEX_WIDTH.W))
  val RegWriteM = Input(Bool())
  val ValidM = Input(Bool())
  val ResultSrcM = Input(Bool())
  val RdW = Input(UInt(REGISTER_INDEX_WIDTH.W))
  val RegWriteW = Input(Bool())
  val ValidW = Input(Bool())
  val PCSrcE = Input(Bool())
  val IFUStallF = Input(Bool())
  val LSUStallM = Input(Bool())
  val CommittedF = Input(Bool())
  val CommittedM = Input(Bool())
  val TrapM = Input(Bool())
  val RedirectIntentM = Input(Bool())
  val RedirectM = Input(Bool())
  val WFIStallM = Input(Bool())
  val StallM = Output(Bool())
  val FlushBusF = Output(Bool())
  val FlushBusM = Output(Bool())
  val ForwardAE = Output(UInt(FORWARD_WIDTH.W))
  val ForwardBE = Output(UInt(FORWARD_WIDTH.W))
  val LoadStallD = Output(Bool())
  val StoreStallD = Output(Bool())
  val StallF = Output(Bool())
  val StallD = Output(Bool())
  val FlushD = Output(Bool())
  val FlushE = Output(Bool())
}

/** 教材 §7.2.2/图 7.19、§9.5.5 的统一组合冒险单元，无状态或流水级。
  * load-use 与共享 DTIM 地址冲突停顿 F/D、冲刷 E；E 级重定向优先于停顿。
  * load 的 M 级结果尚未进入写回前递路径，必须等待 W；x0 从不前递或造成依赖。
  */
final class Hazard extends RawModule {
  val io = IO(new HazardIO)

  private def forward(source: UInt): UInt = {
    val MatchM = io.ValidM && io.RegWriteM && !io.ResultSrcM &&
      io.RdM =/= 0.U(REGISTER_INDEX_WIDTH.W) && source === io.RdM
    val MatchW = io.ValidW && io.RegWriteW &&
      io.RdW =/= 0.U(REGISTER_INDEX_WIDTH.W) && source === io.RdW
    Mux(!io.ValidE || source === 0.U(REGISTER_INDEX_WIDTH.W), FORWARD_NONE.U(FORWARD_WIDTH.W),
      Mux(MatchM, FORWARD_M.U(FORWARD_WIDTH.W),
        Mux(MatchW, FORWARD_W.U(FORWARD_WIDTH.W), FORWARD_NONE.U(FORWARD_WIDTH.W))))
  }
  io.ForwardAE := forward(io.Rs1E)
  io.ForwardBE := forward(io.Rs2E)

  val MatchDE = io.RdE =/= 0.U(REGISTER_INDEX_WIDTH.W) &&
    ((io.UsesRs1D && io.Rs1D === io.RdE) ||
      (io.UsesRs2D && io.Rs2D === io.RdE))
  io.LoadStallD := io.ValidD && io.ValidE &&
    (io.MemRWE(MEM_READ_BIT) || io.LateResultE) && MatchDE
  io.StoreStallD := io.ValidD && io.ValidE &&
    io.MemRWE(MEM_WRITE_BIT) && io.MemRWD(MEM_READ_BIT)
  val StructuralStallD = io.LoadStallD || io.StoreStallD
  // Cancellation uses raw intent, never the StallM-qualified RedirectM.
  // Accepted requests retain ownership until completion; W drains independently.
  val CancelF = io.RedirectIntentM && !io.CommittedF
  val CancelM = io.TrapM && !io.CommittedM
  io.FlushBusF := CancelF
  io.FlushBusM := CancelM
  io.StallM := io.WFIStallM || (io.IFUStallF && !CancelF) ||
    (io.LSUStallM && !CancelM)
  io.StallD := (StructuralStallD && !io.PCSrcE) || io.StallM
  io.StallF := io.StallD
  io.FlushD := io.PCSrcE || io.RedirectM
  io.FlushE := io.PCSrcE || StructuralStallD
}
