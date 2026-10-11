package riscvpipeline

/** Independent test specifications: never derive these values from production constants.
  * Raw program fixtures and exhaustive illegal vectors remain literal grounding examples.
  */
private[riscvpipeline] object TestEncoding {
  object CSR {
    val SSTATUS = 0x100
    val SIE = 0x104
    val STVEC = 0x105
    val SCOUNTEREN = 0x106
    val SENVCFG = 0x10a
    val SSCRATCH = 0x140
    val SEPC = 0x141
    val SCAUSE = 0x142
    val STVAL = 0x143
    val SIP = 0x144
    val STIMECMP = 0x14d
    val STIMECMPH = 0x15d
    val SATP = 0x180
    val MSTATUS = 0x300
    val MISA = 0x301
    val MEDELEG = 0x302
    val MIDELEG = 0x303
    val MIE = 0x304
    val MTVEC = 0x305
    val MCOUNTEREN = 0x306
    val MENVCFG = 0x30a
    val MSTATUSH = 0x310
    val MENVCFGH = 0x31a
    val MCOUNTINHIBIT = 0x320
    val MHPMEVENT3 = 0x323
    val MHPMEVENT31 = 0x33f
    val MSCRATCH = 0x340
    val MEPC = 0x341
    val MCAUSE = 0x342
    val MTVAL = 0x343
    val MIP = 0x344
    val PMPCFG0 = 0x3a0
    val MCYCLE = 0xb00
    val MINSTRET = 0xb02
    val MHPMCOUNTER3 = 0xb03
    val MHPMCOUNTER4 = 0xb04
    val MCYCLEH = 0xb80
    val MINSTRETH = 0xb82
    val MHPMCOUNTER3H = 0xb83
    val CYCLE = 0xc00
    val TIME = 0xc01
    val INSTRET = 0xc02
    val HPMCOUNTER3 = 0xc03
    val HPMCOUNTER31 = 0xc1f
    val CYCLEH = 0xc80
    val TIMEH = 0xc81
    val INSTRETH = 0xc82
    val HPMCOUNTER3H = 0xc83
    val HPMCOUNTER31H = 0xc9f
    val MVENDORID = 0xf11
    val MARCHID = 0xf12
    val MIMPID = 0xf13
    val MHARTID = 0xf14
    val MCONFIGPTR = 0xf15
  }
  object Opcode {
    val LOAD = 0x03
    val OP_IMM = 0x13
    val AUIPC = 0x17
    val STORE = 0x23
    val OP = 0x33
    val LUI = 0x37
    val BRANCH = 0x63
    val JALR = 0x67
    val JAL = 0x6f
    val SYSTEM = 0x73
  }
  object SystemFunction {
    val ECALL = 0x000
    val EBREAK = 0x001
    val SRET = 0x102
    val WFI = 0x105
    val MRET = 0x302
  }
  object ALUFunction {
    val ADD_SUB = 0
    val SLL = 1
    val SLT = 2
    val SLTU = 3
    val XOR = 4
    val SR = 5
    val OR = 6
    val AND = 7
  }
  object BranchFunction {
    val EQ = 0
    val NE = 1
    val LT = 4
    val GE = 5
    val LTU = 6
    val GEU = 7
  }
  object MemoryFunction {
    val BYTE = 0
    val HALF = 1
    val WORD = 2
    val DOUBLE = 3
    val BYTE_UNSIGNED = 4
    val HALF_UNSIGNED = 5
    val WORD_UNSIGNED = 6
  }
  object CSRFunction {
    val RW = 1
    val RS = 2
    val RC = 3
    val RWI = 5
    val RSI = 6
    val RCI = 7
  }
  object Immediate {
    val I = 0
    val S = 1
    val B = 2
    val J = 3
    val U = 4
  }
  object Control {
    val ALU_ADD = 0
    val ALU_FUNCTION = 1
    val ALU_SUBTRACT = 2
    val ALU_FUNCTION_SUBTRACT = 3
    val SRC_REGISTERS = 0
    val SRC_IMMEDIATE = 1
    val SRC_PC_IMMEDIATE = 3
    val MEM_NONE = 0
    val MEM_WRITE = 1
    val MEM_READ = 2
    val FORWARD_REGISTER = 0
    val FORWARD_W = 1
    val FORWARD_M = 2
  }
  object Privilege {
    val USER = 0
    val SUPERVISOR = 1
    val RESERVED = 2
    val MACHINE = 3
  }
  object Cause {
    val INSTR_MISALIGNED = 0
    val INSTR_ACCESS = 1
    val ILLEGAL_INSTR = 2
    val BREAKPOINT = 3
    val LOAD_MISALIGNED = 4
    val LOAD_ACCESS = 5
    val STORE_MISALIGNED = 6
    val STORE_ACCESS = 7
    val ECALL_USER = 8
    val ECALL_SUPERVISOR = 9
    val ECALL_MACHINE = 11
    val INSTR_PAGE = 12
    val LOAD_PAGE = 13
    val STORE_PAGE = 15
    val SUPERVISOR_SOFTWARE_IRQ = 1
    val MACHINE_SOFTWARE_IRQ = 3
    val SUPERVISOR_TIMER_IRQ = 5
    val MACHINE_TIMER_IRQ = 7
    val SUPERVISOR_EXTERNAL_IRQ = 9
    val MACHINE_EXTERNAL_IRQ = 11
    val INPUT_INTERRUPT_FLAG = 32
  }
  object Mask {
    val BYTE = 0xff
    val REGISTER_FIELD = 0x1f
    val IMM12 = 0xfff
    val CSR_READ_ONLY_START = 0xc00
    val BRANCH_IMMEDIATE = 0x1fff
    val JUMP_IMMEDIATE = 0x1fffff
    val FUNCT7_ALTERNATE = 0x20
    val SSIP = 0x002
    val MSIP = 0x008
    val STIP = 0x020
    val MTIP = 0x080
    val SEIP = 0x200
    val MEIP = 0x800
    val SUPERVISOR_INTERRUPTS = 0x222
    val MACHINE_INTERRUPTS = 0x888
    val ALL_INTERRUPTS = 0xaaa
    val INTERRUPT_FIELDS = 0xfff
    val BASE_EXCEPTION_DELEGATION = 0x03ff
    val VM_EXCEPTION_DELEGATION = 0xb3ff
    val SFENCE_VMA: BigInt = BigInt("fe007fff", 16)
  }
}
