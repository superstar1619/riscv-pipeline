package riscvpipeline.privileged

import chisel3._
import chisel3.util.{Cat, MuxLookup}

/** 周期/退休/HPM寄存器与外部时间读视图，控制状态由Machine/Supervisor提供。 */
final class CSRCountersIO extends Bundle {
  val ValidM = Input(Bool())
  val StallW = Input(Bool())
  val FlushM = Input(Bool())
  val TrapM = Input(Bool())
  val CSRWriteM = Input(Bool())
  val CSRAdrM = Input(UInt(12.W))
  val CSRWriteValM = Input(UInt(32.W))
  val PrivilegeModeW = Input(UInt(2.W))
  val InstrRetiredM = Input(Bool())
  val HPMEventsM = Input(UInt(32.W))
  val MCOUNTINHIBIT_REGW = Input(UInt(32.W))
  val MCOUNTEREN_REGW = Input(UInt(32.W))
  val SCOUNTEREN_REGW = Input(UInt(32.W))
  val TIME_REGW = Input(UInt(64.W))

  val CSRCExistsM = Output(Bool())
  val CSRCAccessAllowedM = Output(Bool())
  val CSRCReadValM = Output(UInt(32.W))
  val CSRCModifyValM = Output(UInt(32.W))
  val MCYCLE_REGW = Output(UInt(64.W))
  val MINSTRET_REGW = Output(UInt(64.W))
  val HPMCOUNTER_REGW = Output(Vec(29, UInt(64.W)))
  val HPMEVENT_REGW = Output(Vec(29, UInt(32.W)))
}

/** RV32计数器，教材§8.2.3.4/§8.4.3.1。显式clk、高有效同步reset，所有状态复位零。
  * HPM事件0不计数，1..32选择调用方资格化的脉冲；未激活槽实现固定零CSR。
  * TIME_REGW需同域一致64位值；本模块不实现时间来源或CPU退休事件提取。
  */
final class CSRCounters(
  supervisorSupported: Boolean = true,
  hpmCounters: Int = 0
) extends RawModule {
  require(hpmCounters >= 0 && hpmCounters <= 29, "HPM counter count must be 0..29")
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new CSRCountersIO)

  val CounterStates = (Seq(0, 2) ++ (3 until hpmCounters + 3)).map { n =>
    n -> withClockAndReset(clk, reset) { RegInit(0.U(64.W)) }
  }.toMap
  val EventSelectors = (3 until hpmCounters + 3).map { n =>
    n -> withClockAndReset(clk, reset) { RegInit(0.U(6.W)) }
  }.toMap
  val CounterNumM = io.CSRAdrM(4, 0)
  val AddressBankM = io.CSRAdrM(11, 5)
  val MachineCounterM = (AddressBankM === (0xb00 >> 5).U(7.W) ||
    AddressBankM === (0xb80 >> 5).U(7.W)) && CounterNumM =/= 1.U(5.W)
  val ShadowCounterM = AddressBankM === (0xc00 >> 5).U(7.W) ||
    AddressBankM === (0xc80 >> 5).U(7.W)
  val EventAddressM = io.CSRAdrM >= "h323".U(12.W) &&
    io.CSRAdrM <= "h33f".U(12.W)
  val MachineModeM = io.PrivilegeModeW === 3.U(2.W)
  val SupervisorModeM = supervisorSupported.B && io.PrivilegeModeW === 1.U(2.W)
  val UserModeM = io.PrivilegeModeW === 0.U(2.W)
  val ShadowPermissionM = ShadowCounterM && io.MCOUNTEREN_REGW(CounterNumM) &&
    (SupervisorModeM || (UserModeM &&
      (!supervisorSupported.B || io.SCOUNTEREN_REGW(CounterNumM))))

  // 固定零HPM也有合法地址；存在性与软件写、模式和访问许可独立。
  io.CSRCExistsM := MachineCounterM || ShadowCounterM || EventAddressM
  io.CSRCAccessAllowedM := io.CSRCExistsM && (MachineModeM || ShadowPermissionM)
  val CounterReadM = MuxLookup(CounterNumM, 0.U(64.W))(
    (CounterStates.toSeq.sortBy(_._1) :+ (1 -> io.TIME_REGW)).map {
      case (n, counter) => n.U(5.W) -> counter
    })
  val EventReadM = MuxLookup(CounterNumM, 0.U(32.W))(
    EventSelectors.toSeq.sortBy(_._1).map { case (n, selector) =>
      n.U(5.W) -> selector.pad(32)
    })
  val ReadValueM = Mux(EventAddressM, EventReadM,
    Mux(io.CSRAdrM(7), CounterReadM(63, 32), CounterReadM(31, 0)))
  io.CSRCReadValM := Mux(io.CSRCAccessAllowedM, ReadValueM, 0.U(32.W))
  io.CSRCModifyValM := io.CSRCReadValM

  val SoftwareWriteM = io.ValidM && !io.StallW && !io.FlushM && !io.TrapM &&
    !reset && io.CSRWriteM && MachineModeM && io.CSRCAccessAllowedM
  val RetireM = io.ValidM && io.InstrRetiredM && !io.StallW && !io.FlushM && !io.TrapM
  for ((n, counter) <- CounterStates.toSeq.sortBy(_._1)) {
    val EventM = if (n == 0) {
      true.B
    } else if (n == 2) {
      RetireM
    } else {
      val Selector = EventSelectors(n)
      Selector =/= 0.U(6.W) && (io.HPMEventsM >> (Selector - 1.U(6.W)))(0)
    }
    val WriteLowM = SoftwareWriteM && io.CSRAdrM === (0xb00 + n).U(12.W)
    val WriteHighM = SoftwareWriteM && io.CSRAdrM === (0xb80 + n).U(12.W)
    withClockAndReset(clk, reset) {
      // 软件半字写覆盖整个计数器的同拍增长，另一半保持沿前值。
      when(WriteLowM) {
        counter := Cat(counter(63, 32), io.CSRWriteValM)
      }.elsewhen(WriteHighM) {
        counter := Cat(io.CSRWriteValM, counter(31, 0))
      }.elsewhen(EventM && !io.MCOUNTINHIBIT_REGW(n)) {
        val Incremented = counter +& 1.U(64.W)
        counter := Incremented(63, 0)
      }
    }
  }
  for ((n, selector) <- EventSelectors.toSeq.sortBy(_._1)) {
    withClockAndReset(clk, reset) {
      when(SoftwareWriteM && io.CSRAdrM === (0x320 + n).U(12.W)) {
        // 本平台仅支持单事件编号0..32，不截取非法整字的低位制造有效事件。
        selector := Mux(io.CSRWriteValM <= 32.U(32.W), io.CSRWriteValM(5, 0),
          0.U(6.W))
      }
    }
  }
  io.MCYCLE_REGW := CounterStates(0)
  io.MINSTRET_REGW := CounterStates(2)
  for (i <- 0 until 29) {
    io.HPMCOUNTER_REGW(i) := CounterStates.getOrElse(i + 3, 0.U(64.W))
    io.HPMEVENT_REGW(i) := EventSelectors.getOrElse(i + 3, 0.U(6.W)).pad(32)
  }
}
