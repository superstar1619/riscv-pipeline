package riscvpipeline.privileged

/** 特权架构的主机常量；UInt 字面量由各模块在自身生成上下文中构造。 */
object PrivilegedConstants {
  object Privilege {
    val MODE_WIDTH: Int = 2
    val MODE_U: Int = 0
    val MODE_S: Int = 1
    val MODE_RESERVED: Int = 2
    val MODE_M: Int = 3
  }

  object ExceptionCause {
    val CAUSE_WIDTH: Int = 5
    val TRAP_CAUSE_WIDTH: Int = 6
    val INTERRUPT_BIT: Int = 5
    val DELEGATION_WIDTH: Int = 16
    val DELEGATION_INDEX_WIDTH: Int = 4
    val INSTRUCTION_MISALIGNED: Int = 0
    val INSTRUCTION_ACCESS: Int = 1
    val ILLEGAL_INSTRUCTION: Int = 2
    val BREAKPOINT: Int = 3
    val LOAD_MISALIGNED: Int = 4
    val LOAD_ACCESS: Int = 5
    val STORE_AMO_MISALIGNED: Int = 6
    val STORE_AMO_ACCESS: Int = 7
    val ECALL_U: Int = 8
    val ECALL_S: Int = 9
    val ECALL_M: Int = 11
    val INSTRUCTION_PAGE: Int = 12
    val LOAD_PAGE: Int = 13
    val STORE_AMO_PAGE: Int = 15
    val SUPERVISOR_MASK: Int = 0x03ff
    val VIRTUAL_MEMORY_MASK: Int = 0xb3ff
  }

  object Interrupt {
    val WIDTH: Int = 12
    val SSIP_BIT: Int = 1
    val MSIP_BIT: Int = 3
    val STIP_BIT: Int = 5
    val MTIP_BIT: Int = 7
    val SEIP_BIT: Int = 9
    val MEIP_BIT: Int = 11
    val SUPERVISOR_MASK: Int = 0x222
    val MACHINE_MASK: Int = 0x888
    val SUPPORTED_MASK: Int = 0xaaa
    val SSIP_MASK: Int = 0x002
    val STCE_SOFTWARE_MASK: Int = 0x202
    val PRIORITY: Seq[Int] = Seq(MEIP_BIT, MSIP_BIT, MTIP_BIT, SEIP_BIT, SSIP_BIT, STIP_BIT)
  }

  object Counter {
    val WIDTH: Int = 64
    val CYCLE: Int = 0
    val TIME: Int = 1
    val INSTRET: Int = 2
    val HPM_FIRST: Int = 3
    val HPM_COUNT: Int = 29
    val INDEX_WIDTH: Int = 5
    val BANK_WIDTH: Int = CSRAddresses.ADDRESS_WIDTH - INDEX_WIDTH
    val HIGH_HALF_BIT: Int = 7
    val EVENT_COUNT: Int = 32
    val EVENT_SELECTOR_WIDTH: Int = 6
    val BASE_ENABLE_MASK: BigInt = BigInt(7)
    val TIME_MASK: BigInt = BigInt(2)
    // 调用方 HPMEventsM 的位编号；CSR 事件选择器采用从 1 开始的编号。
    val EVENT_RETIRED: Int = 0
    val EVENT_LOAD: Int = 1
    val EVENT_STORE: Int = 2
    val EVENT_LOAD_STALL: Int = 3
    val EVENT_STORE_STALL: Int = 4
  }

  object StatusField {
    val SIE_BIT: Int = 1
    val MIE_BIT: Int = 3
    val SPIE_BIT: Int = 5
    val UBE_BIT: Int = 6
    val MPIE_BIT: Int = 7
    val SPP_BIT: Int = 8
    val MPP_LOW: Int = 11
    val MPP_HIGH: Int = 12
    val MPRV_BIT: Int = 17
    val SUM_BIT: Int = 18
    val MXR_BIT: Int = 19
    val TVM_BIT: Int = 20
    val TW_BIT: Int = 21
    val TSR_BIT: Int = 22
    val SBE_HIGH_BIT: Int = 4
    val MBE_HIGH_BIT: Int = 5
    val FS_WIDTH: Int = 2
    val MPP_MASK: BigInt = BigInt("1800", 16)
    val SUPERVISOR_BASE_MASK: BigInt = BigInt("80122", 16)
    val MACHINE_BASE_MASK: BigInt = BigInt("221888", 16)
    val SUPERVISOR_CONTROL_MASK: BigInt = BigInt("500000", 16)
    val SUM_MASK: BigInt = BigInt("40000", 16)
    val UBE_MASK: BigInt = BigInt("40", 16)
    val SBE_HIGH_MASK: Int = 0x10
    val MBE_HIGH_MASK: Int = 0x20
    val MACHINE_STACK_MASK: BigInt = BigInt("1888", 16)
    val SUPERVISOR_STACK_MASK: BigInt = BigInt("122", 16)
    val SUPERVISOR_RETURN_MASK: BigInt = BigInt("20122", 16)
    val MPRV_CLEAR_MASK: BigInt = BigInt("fffdffff", 16)
  }

  object TrapVector {
    val ALIGNMENT_BITS: Int = 2
    val MODE_WIDTH: Int = 2
    val MODE_VECTORED: Int = 1
    val VECTOR_ALIGNMENT_BITS: Int = 6
  }

  object EnvironmentConfig {
    val WIDTH: Int = 64
    val FIOM_MASK: Int = 1
    val STCE_BIT: Int = 63
    val STCE_HIGH_MASK: BigInt = BigInt("80000000", 16)
  }

  object SupervisorTranslation {
    val ASID_MAX_BITS: Int = 9
    val ASID_LOW: Int = 22
    val MODE_BIT: Int = 31
    val MODE_PPN_MASK: BigInt = BigInt("803fffff", 16)
  }
}
