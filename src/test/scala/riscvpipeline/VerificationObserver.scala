package riscvpipeline

import chisel3._
import chisel3.util.RegEnable
import chisel3.stage.ChiselStage
import chiseltest.experimental.expose
import riscvpipeline.config.CpuConfig

/** Test-scope observation only. Production modules and ports are unchanged.
  * M metadata is sampled on the same edge as the actual store and W transfer.
  * Older W instructions may retire while a younger M instruction traps.
  */
class VerificationObserver(config: CpuConfig, compareProduction: Boolean = false) extends Module {
  val io = IO(new RiscvPipelineIO(config))
  val dut = Module(new RiscvPipeline(config))
  dut.clk := clock; dut.reset := reset.asBool; io <> dut.io
  val validM = expose(dut.ieu.io.ValidM)
  val validW = expose(dut.ieu.dp.io.ValidW)
  val pcM = expose(dut.ieu.io.PCM)
  val instrM = expose(dut.ieu.io.InstrM)
  val trapM = expose(dut.TrapM)
  val stallM = expose(dut.StallM)
  val redirectM = expose(dut.RedirectM)
  val regWriteW = expose(dut.ieu.dp.io.RegWriteW)
  val rdW = expose(dut.ieu.dp.io.RdW)
  val resultW = expose(dut.ieu.dp.ResultW)
  val x0 = expose(dut.ieu.dp.rf.Registers(0))
  val pcF = expose(dut.ifu.io.PCF)
  val pcD = expose(dut.ifu.io.PCD)
  val instrD = expose(dut.ifu.io.InstrD)
  val validD = expose(dut.ifu.io.ValidD)
  val validE = expose(dut.ieu.dp.io.ValidE)
  val stallF = expose(dut.hazard.io.StallF)
  val stallD = expose(dut.hazard.io.StallD)
  val flushD = expose(dut.hazard.io.FlushD)
  val flushE = expose(dut.hazard.io.FlushE)
  val redirectE = expose(dut.ieu.io.PCSrcE)
  val forwardA = expose(dut.hazard.io.ForwardAE)
  val forwardB = expose(dut.hazard.io.ForwardBE)
  val rs1E = expose(dut.ieu.dp.io.Rs1E)
  val rs2E = expose(dut.ieu.dp.io.Rs2E)
  val loadStall = expose(dut.hazard.io.LoadStallD)
  val storeStall = expose(dut.hazard.io.StoreStallD)
  val rdM = expose(dut.ieu.dp.io.RdM)
  val writeM = expose(dut.ieu.dp.io.RegWriteM)
  val lateM = expose(dut.ieu.dp.io.ResultSrcM)

  val acceptM = validM && !stallM && !trapM && !reset.asBool
  val expectedW = RegNext(acceptM, false.B)
  val sampledPC = RegEnable(pcM, 0.U(32.W), acceptM)
  val sampledInstr = RegEnable(instrM, 0.U(32.W), acceptM)
  val sampledAdr = RegEnable(io.IEUAdr, 0.U(32.W), acceptM)
  val sampledData = RegEnable(io.WriteData, 0.U(32.W), acceptM)
  val sampledRW = RegEnable(io.MemRW, 0.U(2.W), acceptM)
  val sampledMask = RegEnable(io.ByteMask, 0.U(4.W), acceptM)
  val branchM = RegEnable(redirectE, false.B, !stallM && !redirectM)
  val sampledBranch = RegEnable(branchM, false.B, acceptM)
  val commitBranchTaken = IO(Output(Bool()))
  commitBranchTaken := sampledBranch
  val commitValid = IO(Output(Bool()))
  val commitPC = IO(Output(UInt(32.W)))
  val commitInstr = IO(Output(UInt(32.W)))
  val commitRd = IO(Output(UInt(5.W)))
  val commitValue = IO(Output(UInt(32.W)))
  val commitAddress = IO(Output(UInt(32.W)))
  val commitData = IO(Output(UInt(32.W)))
  val commitRW = IO(Output(UInt(2.W)))
  val commitMask = IO(Output(UInt(4.W)))
  commitValid := validW && !reset.asBool
  commitPC := sampledPC; commitInstr := sampledInstr
  commitRd := Mux(regWriteW, rdW, 0.U)
  commitValue := Mux(regWriteW && rdW =/= 0.U, resultW, 0.U)
  commitAddress := sampledAdr; commitData := sampledData
  commitRW := sampledRW; commitMask := sampledMask

  // Preserve E identity through stalls; emit micro-events only on actual advance.
  val eInstr = RegInit(0.U(32.W))
  when(redirectM || (flushE && !stallM)) { eInstr := 0.U }
    .elsewhen(!stallM) { eInstr := instrD }
  val eventInstr = IO(Output(UInt(32.W))); eventInstr := eInstr
  val eventAdvance = IO(Output(Bool()))
  eventAdvance := validE && !stallM && !redirectM && !reset.asBool

  val previousPCF = RegNext(pcF)
  val previousPCD = RegNext(pcD)
  val previousInstrD = RegNext(instrD)
  val heldF = RegNext(stallF && !redirectE && !redirectM && !reset.asBool, false.B)
  val heldD = RegNext(stallD && !flushD && !reset.asBool, false.B)
  val killedD = RegNext(flushD && !reset.asBool, false.B)
  val killedE = RegNext((redirectM || (flushE && !stallM)) && !reset.asBool, false.B)
  when(!reset.asBool) {
    assert(validW === expectedW, "W valid must match one accepted M event")
    assert(!regWriteW || validW, "writeback requires a valid W instruction")
    assert(!io.MemWrite || (validM && !trapM && !stallM), "store from invalid/suppressed M")
    assert(io.MemWrite === io.ByteMask.orR, "store enable/mask mismatch")
    assert(x0 === 0.U, "architectural x0 changed")
    assert(rs1E =/= 0.U || forwardA === 0.U, "x0 forwarded on A")
    assert(rs2E =/= 0.U || forwardB === 0.U, "x0 forwarded on B")
    when(heldF) { assert(pcF === previousPCF, "F changed while held") }
    when(heldD) { assert(pcD === previousPCD && instrD === previousInstrD, "D changed while held") }
    when(killedD) { assert(!validD, "D flush did not cancel valid") }
    when(killedE) { assert(!validE, "E flush did not cancel valid") }
    when(validE && validM && writeM && !lateM && rdM =/= 0.U) {
      when(rs1E === rdM) { assert(forwardA === 2.U, "M must win forwarding priority A") }
      when(rs2E === rdM) { assert(forwardB === 2.U, "M must win forwarding priority B") }
    }
  }
  if(compareProduction) {
    val reference = Module(new RiscvPipeline(config))
    reference.clk := clock; reference.reset := reset.asBool
    // Before GPR initialization only effective outputs have architectural meaning.
    val retired = RegInit(0.U(7.W))
    when(commitValid && retired =/= 127.U) { retired := retired + 1.U }
    when(!reset.asBool) {
      assert(reference.io.MemWrite === io.MemWrite && reference.io.ByteMask === io.ByteMask)
      when(io.MemWrite || retired >= 64.U) {
        assert(reference.io.asUInt === io.asUInt, "observer changed production public outputs")
      }
    }
  }
}

object GenerateVerificationObserver extends App {
  require(args.length == 2, "targetDir imagePath")
  val config = CpuConfig(imemDepth=524288, dmemDepth=32768,
    resetVector=BigInt("80000000",16), instructionInitFile=Some(args(1)))
  (new ChiselStage).emitVerilog(new VerificationObserver(config, compareProduction=true),
    Array("--target-dir",args(0)))
}
