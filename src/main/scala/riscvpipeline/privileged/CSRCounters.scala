package riscvpipeline.privileged

import chisel3._
import chisel3.util.{Cat, MuxLookup}
import riscvpipeline.isa.Architecture._
import CSRAddresses._
import PrivilegedConstants.Privilege._
import PrivilegedConstants._

/** 周期/退休/HPM寄存器与外部时间读视图，控制状态由Machine/Supervisor提供。 */
final class CSRCountersIO extends Bundle {
  val ValidM = Input(Bool())
  val StallW = Input(Bool())
  val FlushM = Input(Bool())
  val TrapM = Input(Bool())
  val CSRWriteM = Input(Bool())
  val CSRAdrM = Input(UInt(ADDRESS_WIDTH.W))
  val CSRWriteValM = Input(UInt(XLEN.W))
  val PrivilegeModeW = Input(UInt(MODE_WIDTH.W))
  val InstrRetiredM = Input(Bool())
  val HPMEventsM = Input(UInt(Counter.EVENT_COUNT.W))
  val MCOUNTINHIBIT_REGW = Input(UInt(XLEN.W))
  val MCOUNTEREN_REGW = Input(UInt(XLEN.W))
  val SCOUNTEREN_REGW = Input(UInt(XLEN.W))
  val TIME_REGW = Input(UInt(Counter.WIDTH.W))

  val CSRCExistsM = Output(Bool())
  val CSRCAccessAllowedM = Output(Bool())
  val CSRCReadValM = Output(UInt(XLEN.W))
  val CSRCModifyValM = Output(UInt(XLEN.W))
  val MCYCLE_REGW = Output(UInt(Counter.WIDTH.W))
  val MINSTRET_REGW = Output(UInt(Counter.WIDTH.W))
  val HPMCOUNTER_REGW = Output(Vec(Counter.HPM_COUNT, UInt(Counter.WIDTH.W)))
  val HPMEVENT_REGW = Output(Vec(Counter.HPM_COUNT, UInt(XLEN.W)))
}

/** RV32计数器，教材§8.2.3.4/§8.4.3.1。显式clk、高有效同步reset，所有状态复位零。
  * HPM事件0不计数，1..32选择调用方资格化的脉冲；未激活槽实现固定零CSR。
  * TIME_REGW需同域一致64位值；本模块不实现时间来源或CPU退休事件提取。
  */
final class CSRCounters(
  supervisorSupported: Boolean = true,
  hpmCounters: Int = 0
) extends RawModule {
  require(hpmCounters >= 0 && hpmCounters <= Counter.HPM_COUNT,
    "HPM counter count must be 0..29")
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val io = IO(new CSRCountersIO)

  val CounterStates = (Seq(Counter.CYCLE, Counter.INSTRET) ++
    (Counter.HPM_FIRST until hpmCounters + Counter.HPM_FIRST)).map { n =>
    n -> withClockAndReset(clk, reset) { RegInit(0.U(Counter.WIDTH.W)) }
  }.toMap
  val EventSelectors = (Counter.HPM_FIRST until hpmCounters + Counter.HPM_FIRST).map { n =>
    n -> withClockAndReset(clk, reset) { RegInit(0.U(Counter.EVENT_SELECTOR_WIDTH.W)) }
  }.toMap
  val SortedCounterStates = CounterStates.toSeq.sortBy(_._1)
  val SortedEventSelectors = EventSelectors.toSeq.sortBy(_._1)
  val CounterNumM = io.CSRAdrM(Counter.INDEX_WIDTH - 1, 0)
  val AddressBankM = io.CSRAdrM(ADDRESS_WIDTH - 1, Counter.INDEX_WIDTH)
  val MachineCounterM =
    (AddressBankM === (MCOUNTER_LOW_BASE >> Counter.INDEX_WIDTH).U(Counter.BANK_WIDTH.W) ||
      AddressBankM === (MCOUNTER_HIGH_BASE >> Counter.INDEX_WIDTH).U(Counter.BANK_WIDTH.W)) &&
      CounterNumM =/= Counter.TIME.U(Counter.INDEX_WIDTH.W)
  val ShadowCounterM =
    AddressBankM === (COUNTER_LOW_BASE >> Counter.INDEX_WIDTH).U(Counter.BANK_WIDTH.W) ||
      AddressBankM === (COUNTER_HIGH_BASE >> Counter.INDEX_WIDTH).U(Counter.BANK_WIDTH.W)
  val EventAddressM = io.CSRAdrM >= MHPMEVENT_FIRST.U(ADDRESS_WIDTH.W) &&
    io.CSRAdrM <= MHPMEVENT_LAST.U(ADDRESS_WIDTH.W)
  val MachineModeM = io.PrivilegeModeW === MODE_M.U(MODE_WIDTH.W)
  val SupervisorModeM = supervisorSupported.B && io.PrivilegeModeW === MODE_S.U(MODE_WIDTH.W)
  val UserModeM = io.PrivilegeModeW === MODE_U.U(MODE_WIDTH.W)
  val ShadowPermissionM = ShadowCounterM && io.MCOUNTEREN_REGW(CounterNumM) &&
    (SupervisorModeM || (UserModeM &&
      (!supervisorSupported.B || io.SCOUNTEREN_REGW(CounterNumM))))

  // 固定零HPM也有合法地址；存在性与软件写、模式和访问许可独立。
  io.CSRCExistsM := MachineCounterM || ShadowCounterM || EventAddressM
  io.CSRCAccessAllowedM := io.CSRCExistsM && (MachineModeM || ShadowPermissionM)
  val CounterReadM = MuxLookup(CounterNumM, 0.U(Counter.WIDTH.W))(
    (SortedCounterStates :+ (Counter.TIME -> io.TIME_REGW)).map {
      case (n, counter) => n.U(Counter.INDEX_WIDTH.W) -> counter
    })
  val EventReadM = MuxLookup(CounterNumM, 0.U(XLEN.W))(
    SortedEventSelectors.map { case (n, selector) =>
      n.U(Counter.INDEX_WIDTH.W) -> selector.pad(XLEN)
    })
  val ReadValueM = Mux(EventAddressM, EventReadM,
    Mux(io.CSRAdrM(Counter.HIGH_HALF_BIT),
      CounterReadM(Counter.WIDTH - 1, XLEN), CounterReadM(XLEN - 1, 0)))
  io.CSRCReadValM := Mux(io.CSRCAccessAllowedM, ReadValueM, 0.U(XLEN.W))
  io.CSRCModifyValM := io.CSRCReadValM

  val SoftwareWriteM = io.ValidM && !io.StallW && !io.FlushM && !io.TrapM &&
    io.CSRWriteM && MachineModeM && io.CSRCAccessAllowedM
  val RetireM = io.ValidM && io.InstrRetiredM && !io.StallW && !io.FlushM && !io.TrapM
  for ((n, counter) <- SortedCounterStates) {
    val EventM = if (n == Counter.CYCLE) {
      true.B
    } else if (n == Counter.INSTRET) {
      RetireM
    } else {
      val Selector = EventSelectors(n)
      Selector =/= 0.U(Counter.EVENT_SELECTOR_WIDTH.W) &&
        (io.HPMEventsM >> (Selector - 1.U(Counter.EVENT_SELECTOR_WIDTH.W)))(0)
    }
    val WriteLowM = SoftwareWriteM && io.CSRAdrM === (MCOUNTER_LOW_BASE + n).U(ADDRESS_WIDTH.W)
    val WriteHighM = SoftwareWriteM &&
      io.CSRAdrM === (MCOUNTER_HIGH_BASE + n).U(ADDRESS_WIDTH.W)
    withClockAndReset(clk, reset) {
      // 软件半字写覆盖整个计数器的同拍增长，另一半保持沿前值。
      when(WriteLowM) {
        counter := Cat(counter(Counter.WIDTH - 1, XLEN), io.CSRWriteValM)
      }.elsewhen(WriteHighM) {
        counter := Cat(io.CSRWriteValM, counter(XLEN - 1, 0))
      }.elsewhen(EventM && !io.MCOUNTINHIBIT_REGW(n)) {
        val Incremented = counter +& 1.U(Counter.WIDTH.W)
        counter := Incremented(Counter.WIDTH - 1, 0)
      }
    }
  }
  for ((n, selector) <- SortedEventSelectors) {
    withClockAndReset(clk, reset) {
      when(SoftwareWriteM && io.CSRAdrM === (MHPMEVENT_BASE + n).U(ADDRESS_WIDTH.W)) {
        // 本平台仅支持单事件编号0..32，不截取非法整字的低位制造有效事件。
        selector := Mux(io.CSRWriteValM <= Counter.EVENT_COUNT.U(XLEN.W),
          io.CSRWriteValM(Counter.EVENT_SELECTOR_WIDTH - 1, 0),
          0.U(Counter.EVENT_SELECTOR_WIDTH.W))
      }
    }
  }
  io.MCYCLE_REGW := CounterStates(Counter.CYCLE)
  io.MINSTRET_REGW := CounterStates(Counter.INSTRET)
  for (i <- 0 until Counter.HPM_COUNT) {
    io.HPMCOUNTER_REGW(i) :=
      CounterStates.getOrElse(i + Counter.HPM_FIRST, 0.U(Counter.WIDTH.W))
    io.HPMEVENT_REGW(i) :=
      EventSelectors.getOrElse(i + Counter.HPM_FIRST, 0.U(Counter.EVENT_SELECTOR_WIDTH.W))
        .pad(XLEN)
  }
}
