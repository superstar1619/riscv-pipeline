package riscvpipeline.hazard

import chisel3._

/** 源寄存器使用、各级有效写入及访存控制；前递编码 00=寄存器、01=W、10=M。 */
final class HazardIO extends Bundle {
  val Rs1D = Input(UInt(5.W))
  val Rs2D = Input(UInt(5.W))
  val UsesRs1D = Input(Bool())
  val UsesRs2D = Input(Bool())
  val ValidD = Input(Bool())
  val MemRWD = Input(UInt(2.W))
  val Rs1E = Input(UInt(5.W))
  val Rs2E = Input(UInt(5.W))
  val RdE = Input(UInt(5.W))
  val ValidE = Input(Bool())
  val MemRWE = Input(UInt(2.W))
  val LateResultE = Input(Bool())
  val RdM = Input(UInt(5.W))
  val RegWriteM = Input(Bool())
  val ValidM = Input(Bool())
  val ResultSrcM = Input(Bool())
  val RdW = Input(UInt(5.W))
  val RegWriteW = Input(Bool())
  val ValidW = Input(Bool())
  val PCSrcE = Input(Bool())
  val ForwardAE = Output(UInt(2.W))
  val ForwardBE = Output(UInt(2.W))
  val LoadStallD = Output(Bool())
  val StoreStallD = Output(Bool())
  val StallF = Output(Bool())
  val StallD = Output(Bool())
  val FlushD = Output(Bool())
  val FlushE = Output(Bool())
}

/** 教材 §7.2.2/图 7.19 的独立组合冒险单元，参考 CVW 的前递和冲刷优先级。
  * load-use 与共享 DTIM 地址冲突停顿 F/D、冲刷 E；E 级重定向优先于停顿。
  * load 的 M 级结果尚未进入写回前递路径，必须等待 W；x0 从不前递或造成依赖。
  */
final class Hazard extends RawModule {
  val io = IO(new HazardIO)

  private def forward(source: UInt): UInt = {
    val MatchM = io.ValidM && io.RegWriteM && !io.ResultSrcM &&
      io.RdM =/= 0.U && source === io.RdM
    val MatchW = io.ValidW && io.RegWriteW && io.RdW =/= 0.U && source === io.RdW
    Mux(!io.ValidE || source === 0.U, 0.U(2.W),
      Mux(MatchM, 2.U(2.W), Mux(MatchW, 1.U(2.W), 0.U(2.W))))
  }
  io.ForwardAE := forward(io.Rs1E)
  io.ForwardBE := forward(io.Rs2E)

  val MatchDE = io.RdE =/= 0.U &&
    ((io.UsesRs1D && io.Rs1D === io.RdE) ||
      (io.UsesRs2D && io.Rs2D === io.RdE))
  io.LoadStallD := io.ValidD && io.ValidE && (io.MemRWE(1) || io.LateResultE) && MatchDE
  io.StoreStallD := io.ValidD && io.ValidE && io.MemRWE(0) && io.MemRWD(1)
  val StructuralStallD = io.LoadStallD || io.StoreStallD
  io.StallD := StructuralStallD && !io.PCSrcE
  io.StallF := io.StallD
  io.FlushD := io.PCSrcE
  io.FlushE := io.PCSrcE || StructuralStallD
}
