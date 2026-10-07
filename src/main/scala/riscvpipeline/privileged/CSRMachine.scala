package riscvpipeline.privileged

import chisel3._
import chisel3.util.{Cat, MuxLookup, is, switch}

/** 机器CSR地址读值与已授权的提交事件；Next*仅用于机器陷阱记录。 */
final class CSRMachineIO extends Bundle {
  val ValidM = Input(Bool())
  val StallW = Input(Bool())
  val FlushM = Input(Bool())
  val CSRWriteM = Input(Bool())
  val MTrapM = Input(Bool())
  val CSRAdrM = Input(UInt(12.W))
  val CSRWriteValM = Input(UInt(32.W))
  val NextEPCM = Input(UInt(32.W))
  val NextCauseM = Input(UInt(6.W))
  val NextXtvalM = Input(UInt(32.W))
  val MSTATUS_REGW = Input(UInt(32.W))
  val MSTATUSH_REGW = Input(UInt(32.W))
  val MIP_REGW = Input(UInt(12.W))
  val MIE_REGW = Input(UInt(12.W))
  val MIP_REGW_writeable = Input(UInt(12.W))

  val CSRMReadValM = Output(UInt(32.W))
  val CSRMModifyValM = Output(UInt(32.W))
  val CSRMExistsM = Output(Bool())
  val MTVEC_REGW = Output(UInt(32.W))
  val MEPC_REGW = Output(UInt(32.W))
  val MCAUSE_REGW = Output(UInt(32.W))
  val MTVAL_REGW = Output(UInt(32.W))
  val MSCRATCH_REGW = Output(UInt(32.W))
  val MCOUNTEREN_REGW = Output(UInt(32.W))
  val MCOUNTINHIBIT_REGW = Output(UInt(32.W))
  val MEDELEG_REGW = Output(UInt(16.W))
  val MIDELEG_REGW = Output(UInt(12.W))
  val MENVCFG_REGW = Output(UInt(64.W))
  val WriteMSTATUSM = Output(Bool())
  val WriteMSTATUSHM = Output(Bool())
  val WriteMIEM = Output(Bool())
  val WriteMIPM = Output(Bool())
}

/** RV32机器CSR叶模块；教材§8.2.3–5、§8.4.3，PMP与计数器本体留给后续。
  * 显式clk和高有效同步reset；当前IALIGN=32，不支持动态修改misa。
  */
final class CSRMachine(
  supervisorSupported: Boolean = true,
  virtualMemorySupported: Boolean = false,
  sstcSupported: Boolean = false,
  vectoredInterruptsSupported: Boolean = true,
  hpmCounters: Int = 0,
  hartId: BigInt = 0
) extends RawModule {
  require(!virtualMemorySupported || supervisorSupported, "VM requires supervisor support")
  require(!sstcSupported || supervisorSupported, "Sstc requires supervisor support")
  require(hpmCounters >= 0 && hpmCounters <= 29, "HPM counter count must be 0..29")
  require(hartId >= 0 && hartId < (BigInt(1) << 32), "hartId must fit unsigned RV32")
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new CSRMachineIO)

  private val COUNTER_MASK = ((BigInt(1) << hpmCounters) - 1) << 3 | BigInt(7)
  private val DELEG_MASK = if (!supervisorSupported) 0
    else if (virtualMemorySupported) 0xb3ff else 0x03ff
  private val MISA = BigInt("40100100", 16) |
    (if (supervisorSupported) BigInt("40000", 16) else BigInt(0))

  // 每个val对应一份状态，复位值0；S/扩展关闭后其常零状态可由工具优化。
  val TVec = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val EPC = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val Cause = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val Tval = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val Scratch = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val CounterEnable = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val CounterInhibit = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val ExceptionDeleg = withClockAndReset(clk, reset) { RegInit(0.U(16.W)) }
  val InterruptDeleg = withClockAndReset(clk, reset) { RegInit(0.U(12.W)) }
  val EnvLow = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }
  val EnvHigh = withClockAndReset(clk, reset) { RegInit(0.U(32.W)) }

  // 存在性只依赖地址/配置，不依赖CSRWriteM，允许CSRAccess据此进行授权。
  val ReadEntries = Seq(
    0x300 -> io.MSTATUS_REGW, 0x301 -> MISA.U(32.W), 0x304 -> io.MIE_REGW.pad(32),
    0x305 -> TVec, 0x306 -> CounterEnable, 0x30a -> EnvLow,
    0x310 -> io.MSTATUSH_REGW, 0x31a -> EnvHigh, 0x320 -> CounterInhibit,
    0x340 -> Scratch, 0x341 -> EPC, 0x342 -> Cause, 0x343 -> Tval,
    0x344 -> io.MIP_REGW.pad(32), 0xf11 -> 0.U(32.W), 0xf12 -> 0.U(32.W),
    0xf13 -> 0.U(32.W), 0xf14 -> hartId.U(32.W), 0xf15 -> 0.U(32.W)
  ) ++ (if (supervisorSupported) Seq(0x302 -> ExceptionDeleg.pad(32),
    0x303 -> InterruptDeleg.pad(32)) else Seq.empty)
  io.CSRMExistsM := ReadEntries.map { case (address, _) =>
    io.CSRAdrM === address.U(12.W)
  }.reduce(_ || _)
  io.CSRMReadValM := MuxLookup(io.CSRAdrM, 0.U(32.W))(
    ReadEntries.map { case (address, value) => address.U(12.W) -> value })
  io.CSRMModifyValM := Mux(io.CSRAdrM === "h344".U(12.W),
    io.MIP_REGW_writeable.pad(32), io.CSRMReadValM)

  val UpdateM = io.ValidM && !io.StallW && !io.FlushM
  val SoftwareWriteM = UpdateM && !reset && !io.MTrapM && io.CSRWriteM && io.CSRMExistsM
  val DirectTVec = Cat(io.CSRWriteValM(31, 2), 0.U(2.W))
  val WrittenTVec = if (vectoredInterruptsSupported) {
    Mux(io.CSRWriteValM(0), Cat(io.CSRWriteValM(31, 6), 0.U(5.W), 1.U(1.W)),
      DirectTVec)
  } else {
    DirectTVec
  }

  withClockAndReset(clk, reset) {
    when(UpdateM) {
      // 精确陷阱记录优先于同拍软件写；三个字段使用同一事件的值。
      when(io.MTrapM) {
        EPC := Cat(io.NextEPCM(31, 2), 0.U(2.W))
        Cause := Cat(io.NextCauseM(5), 0.U(26.W), io.NextCauseM(4, 0))
        Tval := io.NextXtvalM
      }.elsewhen(SoftwareWriteM) {
        switch(io.CSRAdrM) {
          is("h305".U) { TVec := WrittenTVec }
          is("h302".U) { ExceptionDeleg := io.CSRWriteValM(15, 0) & DELEG_MASK.U(16.W) }
          is("h303".U) {
            InterruptDeleg := io.CSRWriteValM(11, 0) &
              (if (supervisorSupported) 0x222 else 0).U(12.W)
          }
          is("h340".U) { Scratch := io.CSRWriteValM }
          is("h341".U) { EPC := Cat(io.CSRWriteValM(31, 2), 0.U(2.W)) }
          is("h342".U) {
            Cause := Cat(io.CSRWriteValM(31), 0.U(26.W), io.CSRWriteValM(4, 0))
          }
          is("h343".U) { Tval := io.CSRWriteValM }
          is("h306".U) { CounterEnable := io.CSRWriteValM & COUNTER_MASK.U(32.W) }
          is("h320".U) {
            CounterInhibit := io.CSRWriteValM & (COUNTER_MASK & ~BigInt(2)).U(32.W)
          }
          is("h30a".U) {
            EnvLow := io.CSRWriteValM & (if (virtualMemorySupported) 1 else 0).U(32.W)
          }
          is("h31a".U) {
            EnvHigh := io.CSRWriteValM &
              (if (sstcSupported) BigInt("80000000", 16) else BigInt(0)).U(32.W)
          }
        }
      }
    }
  }

  io.MTVEC_REGW := TVec
  io.MEPC_REGW := EPC
  io.MCAUSE_REGW := Cause
  io.MTVAL_REGW := Tval
  io.MSCRATCH_REGW := Scratch
  io.MCOUNTEREN_REGW := CounterEnable
  io.MCOUNTINHIBIT_REGW := CounterInhibit
  io.MEDELEG_REGW := ExceptionDeleg
  io.MIDELEG_REGW := InterruptDeleg
  io.MENVCFG_REGW := Cat(EnvHigh, EnvLow)
  io.WriteMSTATUSM := SoftwareWriteM && io.CSRAdrM === "h300".U(12.W)
  io.WriteMSTATUSHM := SoftwareWriteM && io.CSRAdrM === "h310".U(12.W)
  io.WriteMIEM := SoftwareWriteM && io.CSRAdrM === "h304".U(12.W)
  io.WriteMIPM := SoftwareWriteM && io.CSRAdrM === "h344".U(12.W)
}
