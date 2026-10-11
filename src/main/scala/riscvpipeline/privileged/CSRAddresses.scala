package riscvpipeline.privileged

/** RV32 CSR 地址；银行基址与槽编号分开，避免把地址编码当作运行时状态。 */
object CSRAddresses {
  val ADDRESS_WIDTH: Int = 12
  val PRIVILEGE_HIGH: Int = 9
  val PRIVILEGE_LOW: Int = 8
  val ACCESS_HIGH: Int = 11
  val ACCESS_LOW: Int = 10
  val READ_ONLY: Int = 3
  val ACCESS_WIDTH: Int = 2

  val SSTATUS: Int = 0x100
  val SIE: Int = 0x104
  val STVEC: Int = 0x105
  val SCOUNTEREN: Int = 0x106
  val SENVCFG: Int = 0x10a
  val SSCRATCH: Int = 0x140
  val SEPC: Int = 0x141
  val SCAUSE: Int = 0x142
  val STVAL: Int = 0x143
  val SIP: Int = 0x144
  val STIMECMP: Int = 0x14d
  val STIMECMPH: Int = 0x15d
  val SATP: Int = 0x180

  val MSTATUS: Int = 0x300
  val MISA: Int = 0x301
  val MEDELEG: Int = 0x302
  val MIDELEG: Int = 0x303
  val MIE: Int = 0x304
  val MTVEC: Int = 0x305
  val MCOUNTEREN: Int = 0x306
  val MENVCFG: Int = 0x30a
  val MSTATUSH: Int = 0x310
  val MENVCFGH: Int = 0x31a
  val MCOUNTINHIBIT: Int = 0x320
  val MHPMEVENT_BASE: Int = 0x320
  val MHPMEVENT_FIRST: Int = 0x323
  val MHPMEVENT_LAST: Int = 0x33f
  val MSCRATCH: Int = 0x340
  val MEPC: Int = 0x341
  val MCAUSE: Int = 0x342
  val MTVAL: Int = 0x343
  val MIP: Int = 0x344

  val MCOUNTER_LOW_BASE: Int = 0xb00
  val MCOUNTER_HIGH_BASE: Int = 0xb80
  val COUNTER_LOW_BASE: Int = 0xc00
  val COUNTER_HIGH_BASE: Int = 0xc80
  val MVENDORID: Int = 0xf11
  val MARCHID: Int = 0xf12
  val MIMPID: Int = 0xf13
  val MHARTID: Int = 0xf14
  val MCONFIGPTR: Int = 0xf15
}
