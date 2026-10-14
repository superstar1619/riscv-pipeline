# 指令位域、编码与数据通路控制

本页按已完成重构后的源码说明常量数值及其使用位置。数值是 Scala 常量，在使用点显式转换为指定宽度的 Chisel 字面量；共享对象不缓存硬件 `Data`。ISA 编码以 [InstructionEncoding.scala](../../../src/main/scala/riscvpipeline/isa/InstructionEncoding.scala) 为准，内部控制编码以 [ControlEncoding.scala](../../../src/main/scala/riscvpipeline/control/ControlEncoding.scala) 为准。

导航：[报告入口](README.md) · [CSR 地址及寄存器映射](02-csr-register-map.md) · [特权字段](03-privileged-fields.md) · [配置与验证](04-configuration-and-validation.md)。本页记录源码行为；标准语义仅用于解释对应关系，不扩大本地实现范围。

## 1. 架构尺寸与指令字段

[Architecture.scala](../../../src/main/scala/riscvpipeline/isa/Architecture.scala) 固定整机 RV32 尺寸。寄存器编号是 `x0`–`x31`，`x0` 恒为零；这些编号属于整数寄存器文件，与 CSR 的 12 位地址空间不同。

| 常量 | 数值 | 架构或硬件含义 |
| --- | --- | --- |
| `XLEN` | 32 | 整数寄存器、地址、整机数据宽度 |
| `BYTE_WIDTH` | 8 | 一个字节的位数 |
| `BYTES_PER_WORD` | 4 | `XLEN / BYTE_WIDTH`，RV32 原生字的字节通道数 |
| `WORD_ALIGNMENT_BITS` | 2 | 原生字的低地址偏移位数，字对齐要求地址 `[1:0]=00` |
| `INSTRUCTION_WIDTH` | 32 | 当前固定长度指令的位数 |
| `INSTRUCTION_BYTES` | 4 | 指令长度，顺序 PC 和链接地址增加 4 字节 |
| `INSTRUCTION_ALIGNMENT_BITS` | 2 | 当前 4 字节指令对齐检查使用的低地址位数 |
| `REGISTER_COUNT` | 32 | 整数寄存器文件项数 |
| `REGISTER_INDEX_WIDTH` | 5 | `rd`、`rs1`、`rs2` 编号宽度；CSR 立即数源也占 5 位 |
| `OPCODE_WIDTH` | 7 | `opcode` 宽度 |
| `FUNCT3_WIDTH` | 3 | `funct3` 宽度 |
| `FUNCT7_WIDTH` | 7 | `funct7` 宽度 |
| `WORD_MASK` | `0xffffffff` | `(1 << XLEN)-1`，32 位全一掩码 |

| 原始指令位 | 字段 | 对应对象或用途 |
| --- | --- | --- |
| `[6:0]` | `opcode` | 指令大类，由 `OP_*` 常量识别 |
| `[11:7]` | `rd` | R/I/U/J 及 CSR 的目的整数寄存器；S/B 格式在此放立即数片段 |
| `[14:12]` | `funct3` | ALU、分支、访存尺寸、CSR 操作种类；U/J 格式在此放立即数片段 |
| `[19:15]` | `rs1` / `zimm` | 常规源整数寄存器编号；CSR 立即数形式解释为无符号 5 位数 |
| `[24:20]` | `rs2` | R/S/B 的第二源整数寄存器；I 格式在此放立即数低位或 `shamt` |
| `[31:25]` | `funct7` | R 型运算及 RV32 立即数移位的附加编码；其他 I 型属于立即数 |
| `[31:20]` | `csr` / `imm[11:0]` | CSR 指令为 CSR 地址；普通 I 型为 12 位立即数 |

上述是物理位段的名称复用，不表示每条指令同时拥有所有字段。[RV32I 官方规范：寄存器及基本指令格式](https://docs.riscv.org/reference/isa/v20240411/unpriv/rv32.html)。

## 2. Opcode 总表

以下全部为 7 位编码；未列出的 opcode 不由整数控制器接受。

| 常量 | 十六进制 | 二进制 `[6:0]` | 对应指令及格式 |
| --- | --- | --- | --- |
| `OP_LOAD` | `0x03` | `0000011` | LB/LH/LW/LBU/LHU，I 型；LD/LWU 仅见独立 64 位模块 |
| `OP_MISC_MEM` | `0x0f` | `0001111` | FENCE、FENCE.I |
| `OP_IMM` | `0x13` | `0010011` | ADDI/SLTI/SLTIU/XORI/ORI/ANDI/SLLI/SRLI/SRAI，I 型 |
| `OP_AUIPC` | `0x17` | `0010111` | AUIPC，U 型，结果为当前 PC 加 U 立即数 |
| `OP_STORE` | `0x23` | `0100011` | SB/SH/SW，S 型；SD 仅见独立 64 位模块 |
| `OP_REG` | `0x33` | `0110011` | ADD/SUB/SLL/SLT/SLTU/XOR/SRL/SRA/OR/AND，R 型 |
| `OP_LUI` | `0x37` | `0110111` | LUI，U 型，结果为 U 立即数 |
| `OP_BRANCH` | `0x63` | `1100011` | BEQ/BNE/BLT/BGE/BLTU/BGEU，B 型 |
| `OP_JALR` | `0x67` | `1100111` | JALR，I 型，`funct3=000` |
| `OP_JAL` | `0x6f` | `1101111` | JAL，J 型 |
| `OP_SYSTEM` | `0x73` | `1110011` | 六种 CSR 指令及 ECALL/EBREAK/MRET/SRET/WFI/SFENCE.VMA |

## 3. ALU 指令及功能字段

表中 R 型 opcode 为 `OP_REG=0x33`，立即数型 opcode 为 `OP_IMM=0x13`。`FUNCT7_BASE=0x00`（`0000000`）；`FUNCT7_SUB_SRA=0x20`（`0100000`）。

| `funct3` 常量 | 数值 / 二进制 | R 型指令及 `funct7` | I 型指令及上七位限制 |
| --- | --- | --- | --- |
| `ALU_ADD_SUB` | 0 / `000` | ADD：`0x00`；SUB：`0x20` | ADDI；`[31:25]` 为立即数的一部分，不限制 |
| `ALU_SLL` | 1 / `001` | SLL：`0x00` | SLLI：`[31:25]=0x00` |
| `ALU_SLT` | 2 / `010` | SLT：`0x00`，有符号比较 | SLTI；上七位不限制 |
| `ALU_SLTU` | 3 / `011` | SLTU：`0x00`，无符号比较 | SLTIU；上七位不限制，立即数仍先符号扩展 |
| `ALU_XOR` | 4 / `100` | XOR：`0x00` | XORI；上七位不限制 |
| `ALU_SRL_SRA` | 5 / `101` | SRL：`0x00`；SRA：`0x20` | SRLI：`0x00`；SRAI：`0x20` |
| `ALU_OR` | 6 / `110` | OR：`0x00` | ORI；上七位不限制 |
| `ALU_AND` | 7 / `111` | AND：`0x00` | ANDI；上七位不限制 |
| `JALR_FUNCT3` | 0 / `000` | 不用于 R 型 | JALR 的独立合法性约束，opcode 为 `0x67` |

寄存器移位的 `rs2[24:20]` 是寄存器编号，移位量取 **该寄存器值的低 5 位**；立即数移位的同一指令位段是 **`shamt[4:0]` 数值**。两者都移动 `rs1` 的值。`[30]`（`funct7[5]`）区分逻辑/算术右移，左移不接受 `0x20`。这里是 RV32 编码，不应用 RV64 六位移位量规则。[RV32I 官方规范：整数计算指令](https://docs.riscv.org/reference/isa/v20240411/unpriv/rv32.html)。

[Controller.scala](../../../src/main/scala/riscvpipeline/ieu/Controller.scala) 检查完整 `funct7`：R 型只接受基础编码及 SUB/SRA 的 `0x20`；I 型仅对移位检查上七位。`funct7=0x01` 的乘除扩展不会因 `funct3` 相同而被接受。
[ALU.scala](../../../src/main/scala/riscvpipeline/ieu/ALU.scala) 在 `ALUOp=0` 时把操作选择强制归零，执行加法；地址生成不受访存/分支 `funct3` 干扰。`IEUAdr` 保留加减结果，`ALUResult` 再按 `funct3` 选择算术、逻辑、移位或比较结果。

## 4. 加载、存储与访问尺寸

LOAD 的 opcode 为 `0x03`、STORE 的 opcode 为 `0x23`，有效地址均为 `rs1 + 符号扩展立即数`；加载写 `rd`，存储取 `rs2` 值的低相应位。

| 加载常量 | `funct3` 数值 / 二进制 | 指令 | 读取及扩展 | 当前 RV32 CPU |
| --- | --- | --- | --- | --- |
| `LOAD_BYTE` | 0 / `000` | LB | 8 位，符号扩展 | 支持 |
| `LOAD_HALF` | 1 / `001` | LH | 16 位，符号扩展 | 支持 |
| `LOAD_WORD` | 2 / `010` | LW | 32 位；独立 64 位读取器中符号扩展 | 支持 |
| `LOAD_DOUBLE` | 3 / `011` | LD | 64 位原样 | 不接受 |
| `LOAD_BYTE_U` | 4 / `100` | LBU | 8 位，零扩展 | 支持 |
| `LOAD_HALF_U` | 5 / `101` | LHU | 16 位，零扩展 | 支持 |
| `LOAD_WORD_U` | 6 / `110` | LWU | 32 位，零扩展到 64 位 | 不接受 |

| 存储常量 | `funct3` 数值 / 二进制 | 指令 | 低位有效数据 | 当前 RV32 CPU |
| --- | --- | --- | --- | --- |
| `STORE_BYTE` | 0 / `000` | SB | `[7:0]` | 支持 |
| `STORE_HALF` | 1 / `001` | SH | `[15:0]` | 支持 |
| `STORE_WORD` | 2 / `010` | SW | `[31:0]` | 支持 |
| `STORE_DOUBLE` | 3 / `011` | SD | `[63:0]` | 不接受 |

LD/LWU/SD 在本实现中对应 RV64I 的访问形式，仅 `SubwordRead(64)`、`SubwordWrite(64)`、`SwByteMask(64)` 的独立实例支持相应编码；整机 RV32 控制器、LSU 和配置不会因此支持 RV64 ISA。[RV64I 官方规范：加载及存储](https://docs.riscv.org/reference/isa/v20240411/unpriv/rv64.html)。

| 尺寸/端序常量 | 数值 | 对应访问及使用方式 |
| --- | --- | --- |
| `BYTE_ACCESS_BYTES` | 1 | SB 字节掩码，基本掩码 `0x1`；对应 LB/LBU 的字节粒度 |
| `HALFWORD_ACCESS_BYTES` | 2 | SH 掩码 `0x3`，地址低 1 位为零；对应 LH/LHU |
| `WORD_ACCESS_BYTES` | 4 | SW 掩码 `0xf`，地址低 2 位为零；对应 LW/LWU |
| `DOUBLEWORD_ACCESS_BYTES` | 8 | 独立 64 位 SD 掩码 `0xff`，地址低 3 位为零；对应 LD |
| `ENDIAN_BYTE_OFFSET_XOR` | 3 / `11` | RV32 大端读取：整字交换后字节偏移 `offset ^ 3` |
| `ENDIAN_HALF_OFFSET_XOR` | 2 / `10` | RV32 大端读取：整字交换后半字偏移 `offset ^ 2` |

[SwByteMask.scala](../../../src/main/scala/riscvpipeline/lsu/SwByteMask.scala) 使用 `(1 << AccessBytes)-1` 生成基本掩码，左移 `ByteOffset` 后裁剪；非法尺寸或未自然对齐输出零。[SubwordWrite.scala](../../../src/main/scala/riscvpipeline/lsu/SubwordWrite.scala) 复制低字节/半字/字到数据通道，掩码决定实际写入位置。
[SubwordRead.scala](../../../src/main/scala/riscvpipeline/lsu/SubwordRead.scala) 选择子字并扩展，非法 `funct3` 输出零；对齐门控在 [LSU.scala](../../../src/main/scala/riscvpipeline/lsu/LSU.scala)，不在读取器内部。
[EndianSwap.scala](../../../src/main/scala/riscvpipeline/privileged/EndianSwap.scala) 只交换 RV32 的四个字节，字节内位顺序不变；写掩码仍采用物理字节偏移。两个 XOR 常量是本地通道索引算法，不是指令字段或 CSR 位编号；端序选择的 CSR 来源见[特权字段](03-privileged-fields.md)。

## 5. 分支比较与跳转

| 分支常量 | `funct3` 数值 / 二进制 | 指令 | `rs1`、`rs2` 条件 |
| --- | --- | --- | --- |
| `BRANCH_EQ` | 0 / `000` | BEQ | 相等 |
| `BRANCH_NE` | 1 / `001` | BNE | 不等 |
| `BRANCH_LT` | 4 / `100` | BLT | 有符号小于 |
| `BRANCH_GE` | 5 / `101` | BGE | 有符号大于等于 |
| `BRANCH_LTU` | 6 / `110` | BLTU | 无符号小于 |
| `BRANCH_GEU` | 7 / `111` | BGEU | 无符号大于等于 |

派生比较常量 `BRANCH_COMPARE_WIDTH=2`（`FUNCT3_WIDTH-1`），选择字段为 `funct3[2:1]`：`BRANCH_COMPARE_EQ=0`、`BRANCH_COMPARE_RESERVED=1`、`BRANCH_COMPARE_LT=2`、`BRANCH_COMPARE_LTU=3`。`funct3[0]` 将 EQ/LT/LTU 结果异或反转，从而得到 NE/GE/GEU。保留比较项输出假，但整个分支合法性检查会拒绝 `010/011`，不会借助反转接受保留指令。

分支/JAL 目标为本条指令 PC 加立即数；JALR 目标为 `rs1 + I立即数` 后清除 bit 0。`JALR_TARGET_MASK=0xfffffffe`，由 `WORD_MASK ^ 1` 推导，在 [Datapath.scala](../../../src/main/scala/riscvpipeline/ieu/Datapath.scala) 使用。JAL/JALR 都把本条指令 `PC+4` 写入 `rd`；掩码清 bit 0 并不消除 bit 1 的 4 字节对齐要求。[RV32I 官方规范：控制转移](https://docs.riscv.org/reference/isa/v20240411/unpriv/rv32.html)。

## 6. 六种 CSR 指令

六种形式均为 `OP_SYSTEM=0x73`；`csr[31:20]` 选 CSR，`rd[11:7]` 接收旧值。它们没有普通 I 型符号立即数：立即数源来自 `zimm[19:15]`，零扩展到 XLEN。[CSRAccess.scala](../../../src/main/scala/riscvpipeline/privileged/CSRAccess.scala) 用指令 bit 14 区分寄存器/立即数源，用 `funct3[1:0]` 区分 RW/RS/RC。

| 常量 | `funct3` 数值 / 二进制 | 指令 | 源及写值 | 读 CSR 条件 | 写 CSR 条件 |
| --- | --- | --- | --- | --- | --- |
| `CSR_RW` | 1 / `001` | CSRRW | `x[rs1]` 替换旧值 | `rd != x0` | 总是有写意图 |
| `CSR_RS` | 2 / `010` | CSRRS | 修改基值 OR `x[rs1]` | 总是有读意图 | `rs1 != x0` |
| `CSR_RC` | 3 / `011` | CSRRC | 修改基值 AND NOT `x[rs1]` | 总是有读意图 | `rs1 != x0` |
| `CSR_RWI` | 5 / `101` | CSRRWI | 零扩展 `zimm` 替换旧值 | `rd != x0` | 总是有写意图 |
| `CSR_RSI` | 6 / `110` | CSRRSI | 修改基值 OR 零扩展 `zimm` | 总是有读意图 | `zimm != 0` |
| `CSR_RCI` | 7 / `111` | CSRRCI | 修改基值 AND NOT 零扩展 `zimm` | 总是有读意图 | `zimm != 0` |

上表的读/写意图对应规范对读取及写入副作用的抑制规则：CSRRW[I] 的 `rd=x0` 抑制读及读副作用；CSRRS/RC 的 `rs1=x0` 或立即数形式的 `zimm=0` 抑制写及写副作用。非零编号的寄存器即使内容为零，CSRRS/RC 仍有写意图。CSRRW 的 `rs1=x0` 和 CSRRWI 的 `zimm=0` 都写零。RS/RC 各形式即使 `rd=x0` 仍读取。[Zicsr 官方规范：访问及副作用规则](https://docs.riscv.org/reference/isa/v20260120/unpriv/zicsr.html)。

本地实际访问还必须通过 `ValidM`、CSR 存在性、附加权限、最低特权及只读检查。非法访问不产生读写使能。RS/RC 使用调用方提供的 `CSRModifyValM` 作修改基值；例如 mip/sip 的外部中断输入不会当作内部可写状态写回。CSR 地址、可写掩码及寄存器的具体更新规则见 [CSR 寄存器映射](02-csr-register-map.md)。
[InstructionDecode.scala](../../../src/main/scala/riscvpipeline/isa/InstructionDecode.scala) 的 `isCSR` 只检查 opcode 和上述六种 `funct3`；存在性/权限由 CSRAccess 处理，保留 `funct3=100` 不属于 CSR 指令。

## 7. Fence 与完整特权指令编码

`FENCE=0`（`funct3=000`）、`FENCE_I=1`（`funct3=001`），共同使用 `OP_MISC_MEM=0x0f`。FENCE 的立即数字段细分为 `fm[31:28]`、`pred[27:24]`、`succ[23:20]`；pred/succ 的四位分别选择 I/O/R/W（位 3/2/1/0）访问类别。常规 FENCE 的 `fm=0`，标准软件把 rs1/rd 置零；这些字段在本地没有对应的控制常量。[RV32I 官方规范：内存排序](https://docs.riscv.org/reference/isa/v20240411/unpriv/rv32.html)。

`isFence` 只检查 opcode/funct3；[IEU.scala](../../../src/main/scala/riscvpipeline/ieu/IEU.scala) 将它们排除于整数非法指令判定，整数控制字为零。当前实现未据此建立缓存刷新或完整多核内存排序机制。FENCE.I 的其余立即数/rs1/rd 位段保留，标准软件置零；FENCE.I 属于 Zifencei 的指令编码。[Zifencei 官方规范](https://docs.riscv.org/reference/isa/v20240411/unpriv/zifencei.html)。

| 完整指令常量 | 32 位十六进制 | `[31:20]` | 固定其余字段 | 本地识别/用途 |
| --- | --- | --- | --- | --- |
| `INSTR_ECALL` | `0x00000073` | `0x000` | `rs1=0, funct3=0, rd=0, opcode=0x73` | 当前支持模式的环境调用异常 |
| `INSTR_EBREAK` | `0x00100073` | `0x001` | 同上 | 断点异常 |
| `INSTR_MRET` | `0x30200073` | `0x302` | 同上 | M 模式陷阱返回，仅 M 可执行 |
| `INSTR_SRET` | `0x10200073` | `0x102` | 同上 | 启用 S 支持时，M 或未受 TSR 限制的 S 模式返回 |
| `INSTR_WFI` | `0x10500073` | `0x105` | 同上 | 等待中断请求；超时、TW 和模式限制另行处理 |

这些指令的 `[31:20]` 是 SYSTEM 子编码，并非 CSR 地址；其寄存器位段要求零，[PrivDecode.scala](../../../src/main/scala/riscvpipeline/privileged/PrivDecode.scala) 用全字比较防止把保留位段当作合法指令。MRET/SRET/WFI 的架构语义可查 [Machine-Level 官方规范](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)；表中限制描述本地译码实现，状态恢复字段另见[特权字段](03-privileged-fields.md)。

`FUNCT7_SFENCE_VMA=0x09`（`0001001`）对应如下位型：

| `[31:25]` | `[24:20]` | `[19:15]` | `[14:12]` | `[11:7]` | `[6:0]` |
| --- | --- | --- | --- | --- | --- |
| `0001001` | `rs2` | `rs1` | `000` | `00000` | `1110011` |

因此 `SFENCE.VMA = 0x12000073 | (rs2 << 20) | (rs1 << 15)`。规范中的 `rs1` 寄存器值指定虚拟地址，`rs2` 寄存器值指定 ASID，编号为 `x0` 的相应操作数表示扩大相应同步范围。[Supervisor 官方规范：SFENCE.VMA](https://docs.riscv.org/reference/isa/v20260120/priv/supervisor.html)。本地只检查上述编码和执行权限：要求 `virtualMemorySupported`，以及 M 或未受 TVM 限制的 S 模式；默认禁用执行请求，没有 MMU/TLB 实现。

## 8. 内部控制编码：与 ISA/CSR 的边界

下面的编码都是本 CPU 的数据通路选择信号，**没有 ISA CSR 地址或 CSR 位域对应物**。同样的数值可出现在不同命名空间，不能把 `IMM_B=2` 当成指令 `funct3` 或 CSR 地址。控制选择关联指令语义，而不是软件可读写状态。

| 控制常量组 | 位宽及全部数值 | 作用与消费者 |
| --- | --- | --- |
| `MEM_WIDTH`；`MEM_NONE`、`MEM_WRITE`、`MEM_READ` | 2；0=`00`、1=`01`、2=`10` | Controller 生成 `MemRW`；Datapath 流水保存；LSU 和 Hazard 分别检测读/写 |
| `MEM_WRITE_BIT`、`MEM_READ_BIT` | 0、1（位编号） | `MemRW[0]` 写使能，`MemRW[1]` 读使能；`11` 没有定义为合法指令控制 |
| `FORWARD_WIDTH`；`FORWARD_NONE`、`FORWARD_W`、`FORWARD_M` | 2；0=`00`、1=`01`、2=`10` | Hazard 生成 `ForwardAE/BE`；Datapath 选择 E 原始值、W 结果或 M 的 IEU 结果 |
| `IMM_SOURCE_WIDTH`；`IMM_I`、`IMM_S`、`IMM_B`、`IMM_J`、`IMM_U` | 3；0=`000`、1=`001`、2=`010`、3=`011`、4=`100` | Controller 的立即数格式选择，Extend 消费；5/6/7 输出零 |
| `ALU_SOURCE_WIDTH`；`ALU_SRC_REG`、`ALU_SRC_IMM`、`ALU_SRC_PC_IMM` | 2；0=`00`、1=`01`、3=`11` | 分别为寄存器+寄存器、寄存器+立即数、PC+立即数；编码 2 无命名控制项 |
| `ALU_SRC_IMM_BIT`、`ALU_SRC_PC_BIT` | 0、1（位编号） | Datapath：bit 0 选择 SrcB 的立即数，bit 1 选择 SrcA 的 PC |
| `ALU_CONTROL_WIDTH` | 2 | `ALUControl={SubArith, ALUOp}`，由 Controller 派生，ALU 消费 |
| `ALU_OP_BIT`、`ALU_SUB_ARITH_BIT` | 0、1（位编号） | bit 0 使能 funct3 运算；bit 1 使能减法路径及算术右移 |

`ALUControl=00` 用于地址/PC 加法，`01` 用于一般整数运算，`11` 用于 SUB/SLT/SLTU/SRA 及对应立即数比较/算术右移。bit 1 让加法器执行 `A + ~B + 1`，比较器用差值/进位得出大小关系，移位器用它选择符号填充；它不是 RISC-V 标志寄存器。

## 9. 立即数选择的精确拼接

[Extend.scala](../../../src/main/scala/riscvpipeline/ieu/Extend.scala) 接收原始指令 `[31:7]` 共 25 位，内部补零恢复架构编号；以下 `inst` 均指原始 32 位指令，`sext` 为符号扩展到输出宽度。

| `ImmSrc` | 扩展公式 | 指令/地址对应关系 |
| --- | --- | --- |
| `IMM_I=0` | `sext(inst[31:20])` | ALU 立即数、load、JALR；移位最终仅用低 5 位 |
| `IMM_S=1` | `sext({inst[31:25],inst[11:7]})` | store 的 12 位地址偏移 |
| `IMM_B=2` | `sext({inst[31],inst[7],inst[30:25],inst[11:8],0})` | 分支的 13 位有符号字节偏移 |
| `IMM_J=3` | `sext({inst[31],inst[19:12],inst[20],inst[30:21],0})` | JAL 的 21 位有符号字节偏移 |
| `IMM_U=4` | `sext({inst[31:12],12'b0})` | LUI/AUIPC 的 32 位立即数 |

B/J 公式已经补 bit 0 的零，Datapath 不再乘 2；U 已补低 12 位零。CSR `zimm` 不经过该扩展器。独立 Extend 允许输出宽度 ≥32，较宽符号扩展不改变整机 RV32 范围。

## 10. 13 位 ControlWord 的九个字段

`CONTROL_WORD_WIDTH=13` 是 Controller 内部打包宽度，布局保持原控制总线；九字段宽度之和为 13。字段默认均为零，其中 `immSrc=IMM_I`、`aluSrc=ALU_SRC_REG`、`memRW=MEM_NONE`。

| Scala 字段 / 输出信号 | `Controls` 位段 | 宽度 | 含义及消费位置 |
| --- | --- | --- | --- |
| `regWrite` / `RegWrite` | `[12]` | 1 | 指令要写整数 `rd`；Datapath 保存到 W，RegFile 实际写入还受有效位/复位及 x0 规则限制 |
| `immSrc` / `ImmSrc` | `[11:9]` | 3 | Extend 选择 I/S/B/J/U 拼接 |
| `aluSrc` / `ALUSrc` | `[8:7]` | 2 | Datapath 在 E 级选择 ALU 的 SrcA/SrcB |
| `aluOp` / 内部 `ALUOp` | `[6]` | 1 | 派生 `ALUControl[0]`，允许 funct3 选择操作，否则强制加法 |
| `aluResultSrc` / `ALUResultSrc` | `[5]` | 1 | Datapath 选择替代 IEU 结果；jump 时 PC+4，否则立即数（LUI） |
| `memRW` / `MemRW` | `[4:3]` | 2 | 流水保存的访存请求；bit 0 写、bit 1 读；LSU 和 Hazard 消费 |
| `resultSrc` / `ResultSrc` | `[2]` | 1 | Datapath 的 M→W 结果选择：1 取 load 数据，0 取 IEU 结果 |
| `branch` / `Branch` | `[1]` | 1 | E 级结合前递后寄存器比较结果决定条件重定向 |
| `jump` / `Jump` | `[0]` | 1 | E 级无条件重定向，同时让替代结果选择 PC+4 |

`packed = (regWrite<<12) | (immSrc<<9) | (aluSrc<<7) | (aluOp<<6) | (aluResultSrc<<5) | (memRW<<3) | (resultSrc<<2) | (branch<<1) | jump`。`MemWrite` 是 `MemRW[0]` 的别名；`PCSrc` 是 jump 或 taken branch 的组合输出；`SubArith` 根据指令字段另外派生，这三个信号不增加 ControlWord 字段。

| 指令组 / Scala 控制项 | 13 位打包值（十六进制） | 相对默认值的非零字段 |
| --- | --- | --- |
| R 型 / `RControl` | `0x1040` | `regWrite, aluOp` |
| I 型 ALU / `IControl` | `0x10c0` | `regWrite, aluSrc=IMM, aluOp` |
| load / `LoadControl` | `0x1094` | `regWrite, aluSrc=IMM, memRW=READ, resultSrc` |
| store / `StoreControl` | `0x0288` | `immSrc=S, aluSrc=IMM, memRW=WRITE` |
| branch / `BranchControl` | `0x0582` | `immSrc=B, aluSrc=PC_IMM, branch` |
| JAL / `JalControl` | `0x17a1` | `regWrite, immSrc=J, aluSrc=PC_IMM, aluResultSrc, jump` |
| JALR / `JalrControl` | `0x10a1` | `regWrite, aluSrc=IMM, aluResultSrc, jump` |
| LUI / `LuiControl` | `0x1820` | `regWrite, immSrc=U, aluResultSrc` |
| AUIPC / `AuipcControl` | `0x1980` | `regWrite, immSrc=U, aluSrc=PC_IMM` |

整数非法指令控制为 `0x0000`，Controller 自身不产生异常。IEU/特权集成另行传递并处理非法指令。CSR 不经过上述九种打包项：特权启用时 IEU 为 CSR 增加整数写回意图，Datapath 在 M→W 用 CSRResult 覆盖通常结果，并把它作为晚结果供 Hazard 判断。

## 11. 前递及依赖判断

[Hazard.scala](../../../src/main/scala/riscvpipeline/hazard/Hazard.scala) 对 A/B 源独立选择：有效 E 指令且源非 x0 时，优先 `FORWARD_M=2`，其次 `FORWARD_W=1`，否则 `FORWARD_NONE=0`。M 候选要求有效、写寄存器、目标非 x0，并且 `!ResultSrcM`；load 和 CSR 的晚结果不能直接用 M 的 IEUResult 前递。W 候选要求有效、写寄存器及目标非 x0。

这些选择同时服务 ALU、分支比较、JALR 基址及 store 写数据。M/W 都匹配时 M 更年轻，因此优先；D 级还有独立 W→D 旁路。没有定义前递编码 `11`，Datapath 的默认选择为原始寄存器值。
IEU 用实际源使用信号判定依赖：R/store/branch 使用 rs2，普通立即数不使用 rs2；CSR 立即数不使用 rs1 寄存器。load 或 CSR 晚结果匹配 D 级真实源时停顿；共享 DTIM 的 store→load 冲突也停顿。E 级重定向优先于这两类停顿，冲刷 D/E。以上都是本地流水线策略，不是指令编码或 CSR 位域。
