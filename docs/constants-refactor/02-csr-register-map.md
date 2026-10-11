# CSR 地址常量与 RISC-V 寄存器映射

本文把代码中的命名常量对应到 RV32 CSR 地址、寄存器和字段，并说明当前实现的实际行为。
地址常量来自 [CSRAddresses.scala](../../src/main/scala/riscvpipeline/privileged/CSRAddresses.scala)；
它们是固定的架构编码，不能作为用户参数随意修改。改变地址会改变软件可见的 CSR 编码。
`supervisorSupported`、`sstcSupported`、`hpmCounters` 等生成参数决定某些地址是否存在或字段是否可写，
不改变这些地址本身。配置入口与约束见 [配置与验证](04-configuration-and-validation.md)。

官方术语和地址归属已对照 [RISC-V CSR 地址表](https://docs.riscv.org/reference/isa/v20260120/priv/priv-csrs.html)。
下表的读写、掩码、复位和提交行为以本项目源码为准；常量重构保留已有实现选择，
这些说明不构成完整 ISA 符合性声明。状态位、中断位、委托掩码与计数许可位的逐位解释见
[特权字段](03-privileged-fields.md)，CSR 指令的读写意图见 [指令与控制](01-instructions-and-controls.md)。

## 地址字段：常量描述的是 CSR 地址，不是 CSR 内部状态

| 常量 | 值 | 对应字段 | 使用方式 |
| --- | --- | --- | --- |
| `ADDRESS_WIDTH` | `12` | `csr[11:0]` | CSR 地址总宽；从指令 `instr[31:20]` 取出 |
| `PRIVILEGE_HIGH` | `9` | `csr[9:8]` 的高位 | 最低访问特权编码的上界 |
| `PRIVILEGE_LOW` | `8` | `csr[9:8]` 的低位 | 最低访问特权编码的下界 |
| `ACCESS_HIGH` | `11` | `csr[11:10]` 的高位 | 地址读写属性的上界 |
| `ACCESS_LOW` | `10` | `csr[11:10]` 的低位 | 地址读写属性的下界 |
| `READ_ONLY` | `3`，即 `0b11` | `csr[11:10]=11` | 对该地址产生写意图时，CSRAccess 判非法 |
| `ACCESS_WIDTH` | `2` | `csr[11:10]` | 只读编码比较的位宽 |

`csr[11:10]` 的 `00/01/10` 都属于地址层面的读写类别，`11` 属于只读类别；
`csr[9:8]` 的 `00/01/11` 分别对应最低 U/S/M 权限，`10` 是 H/VS 地址类别。
本实现不支持 H/VS，拒绝最低权限编码 `10` 和当前模式编码 `10`。
这些约定及较高特权可以访问较低特权 CSR 的规则来自
[官方 CSR 地址映射约定](https://docs.riscv.org/reference/isa/v20260120/priv/priv-csrs.html)。

[CSRAccess.scala](../../src/main/scala/riscvpipeline/privileged/CSRAccess.scala) 还要求地址存在、
叶模块附加许可成立、当前特权足够；仅有合法地址编码并不代表已实现该 CSR。
只读检查使用实际写意图，因此写只读影子地址会报非法，而只读访问可通过。
地址为读写类别但字段固定不变，例如 `misa` 或未激活机器 HPM 槽，合法写入可以被忽略。

## 机器陷阱、控制及环境 CSR

下表全部是最低 M 权限的读写地址。地址入口由
[CSRMachine.scala](../../src/main/scala/riscvpipeline/privileged/CSRMachine.scala) 译码；
共享状态实际存于 [CSRStatus.scala](../../src/main/scala/riscvpipeline/privileged/CSRStatus.scala) 或
[CSRInterrupt.scala](../../src/main/scala/riscvpipeline/privileged/CSRInterrupt.scala)。
“复位零”指同步复位后自有状态为零；`mip` 等包含外部输入的读值仍可能非零。

| 常量 | 地址 | ISA 寄存器／字段 | 主要含义 | 模块与实际访问、配置、复位行为 |
| --- | --- | --- | --- | --- |
| `MSTATUS` | `0x300` | `mstatus` | 机器状态及陷阱状态栈 | Machine 转发读写到 Status；共享状态复位零；支持字段见 03 |
| `MISA` | `0x301` | `misa.MXL`、扩展位 | 报告整数宽度及支持的单字母扩展 | Machine 固定读 RV32 I/U/(S)；合法软件写忽略；无可变状态 |
| `MEDELEG` | `0x302` | `medeleg[15:0]` | 按异常原因委托到 S | 仅 S 开启时存在；复位零；叶模块无 VM 用 `0x03ff`，有 VM 用 `0xb3ff` 掩码 |
| `MIDELEG` | `0x303` | `mideleg[11:0]` | 按中断原因委托到 S | 仅 S 开启时存在；复位零；可写掩码 `0x222`，读零扩展到 32 位 |
| `MIE` | `0x304` | `mie` | 中断源使能 | Machine 转发到 Interrupt；内部使能复位零；掩码随 S 支持变化 |
| `MTVEC` | `0x305` | `mtvec.BASE/MODE` | 机器陷阱入口基址及模式 | Machine 自有；复位零；Direct 四字节对齐，Vectored 采用 64 字节对齐 |
| `MCOUNTEREN` | `0x306` | `mcounteren.CY/TM/IR/HPMn` | 向较低特权开放计数读取 | Machine 自有；复位零；保留基本三位及已配置 HPM 位；许可规则见 03 |
| `MENVCFG` | `0x30a` | `menvcfg[31:0]`，`FIOM` | 较低特权执行环境配置低半 | Machine 自有；复位零；仅叶模块 VM 开启时 bit 0 可写；集成配置固定零 |
| `MSTATUSH` | `0x310` | RV32 `mstatush` | 状态寄存器高半视图 | Machine 转发到 Status；复位零；端序字段随配置变化；不支持字段读零 |
| `MENVCFGH` | `0x31a` | RV32 `menvcfg[63:32]`，`STCE` | 执行环境配置高半 | Machine 自有；复位零；仅 Sstc 开启时高半 bit 31 可写，否则固定零 |
| `MCOUNTINHIBIT` | `0x320` | `mcountinhibit.CY/IR/HPMn` | 禁止对应计数器增长 | Machine 自有；复位零；TM bit 1 固定零；控制增长而非访问权限 |
| `MSCRATCH` | `0x340` | `mscratch[31:0]` | 软件保存机器陷阱上下文 | Machine 自有；全 32 位读写；复位零；陷阱不自动覆盖它 |
| `MEPC` | `0x341` | `mepc[31:0]` | 陷阱返回地址 | Machine 自有；复位零；软件写和陷阱写都清 `[1:0]`；陷阱写优先 |
| `MCAUSE` | `0x342` | `mcause.Interrupt/Exception Code` | 机器陷阱原因 | Machine 自有；复位零；保存 bit 31 和 `[4:0]`，`[30:5]` 为零 |
| `MTVAL` | `0x343` | `mtval[31:0]` | 陷阱辅助值 | Machine 自有；复位零；全字软件读写；陷阱记录 `NextXtvalM` |
| `MIP` | `0x344` | `mip` | 中断源 pending 状态 | Machine 转发到 Interrupt；软件状态复位零；读值合并外部输入；修改基值仅取软件状态 |

`mcountinhibit` 与 `mcounteren` 是两个不同的寄存器：一个停止增长，一个授权读取。
例如默认 HPM 个数为零时，两者可写掩码分别为 `0x5` 与 `0x7`。
委托、状态以及中断的全部位号和读改写掩码见 [03](03-privileged-fields.md)。

### 固定 misa 值的逐字段拆解

`CSRMachine.MISA_BASE=0x40100100`，`CSRMachine.MISA_SUPERVISOR_MASK=0x00040000`。
代码通过 OR 组合固定读值；这些也是架构报告用的常量，不是运行时写配置。

| 字段 | RV32 位置 | 对应值／掩码 | 本实现报告含义 |
| --- | --- | --- | --- |
| `MXL` | `[31:30]` | `01`，贡献 `0x40000000` | 整数宽度 RV32 |
| `I` | bit `8` | `0x00000100` | 基础整数 ISA |
| `U` | bit `20` | `0x00100000` | 支持 U 模式 |
| `S` | bit `18` | `0x00040000` | 仅 `supervisorSupported=true` 时置位 |
| 其余位 | 其余位置 | `0` | 本模块未报告其他单字母扩展 |

因此 S 关闭时读 `0x40100100`，S 开启时读 `0x40140100`。
MXL 与字母位定义见 [官方 misa 定义](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)。
多字母扩展不能直接从这些字母位完整判断；这个报告值也不替代整机指令能力验证。

### 机器标识 CSR

以下地址均为最低 M 权限、地址属性只读，统一由 Machine 读取。
它们没有可写寄存器状态，因而不使用“复位后保持某值”的描述。

| 常量 | 地址 | ISA 寄存器／字段 | 主要含义 | 本项目实际行为 |
| --- | --- | --- | --- | --- |
| `MVENDORID` | `0xf11` | `mvendorid.Bank[31:7]/Offset[6:0]` | JEDEC 厂商标识 | 固定读零，未提供本项目厂商编号 |
| `MARCHID` | `0xf12` | `marchid` | 微架构标识 | 固定读零，未分配架构编号 |
| `MIMPID` | `0xf13` | `mimpid` | 实现版本标识 | 固定读零，未提供实现版本编号 |
| `MHARTID` | `0xf14` | `mhartid` | 硬件线程编号 | 读构造参数 `hartId`，默认 `0`；限制为无符号 32 位值 |
| `MCONFIGPTR` | `0xf15` | `mconfigptr` | 配置数据结构的物理地址指针 | 固定读零；本项目没有提供该配置数据结构 |

`mconfigptr` 指向可供软件发现 hart、平台和配置的数据结构，零值允许表示结构不存在或需使用其他定位机制。
它不是 HPM 配置、CPU 参数包或 `hartId` 的地址；RV32 非零指针须至少四字节对齐。
定义见 [官方 mconfigptr 章节](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)。

## 监督 CSR 与共享视图

以下都是最低 S 权限、地址属性读写；M 模式也可访问。
全部由 [CSRSupervisor.scala](../../src/main/scala/riscvpipeline/privileged/CSRSupervisor.scala) 译码，
`supervisorSupported=false` 时全部不存在。Sstc 两个地址另受 `sstcSupported` 控制。
表中“共享”表示读写转发到同一份机器状态，不额外创建副本。

| 常量 | 地址 | ISA 寄存器／字段 | 主要含义 | 模块与实际访问、配置、复位行为 |
| --- | --- | --- | --- | --- |
| `SSTATUS` | `0x100` | `sstatus` | S 可见的状态子集 | Supervisor 转发到 Status；共享 `mstatus`；复位零；字段见 03 |
| `SIE` | `0x104` | `sie` | S 中断使能视图 | 转发到 Interrupt；共享 `mie`；仅委托位可见／可写；内部状态复位零 |
| `STVEC` | `0x105` | `stvec.BASE/MODE` | S 陷阱入口 | Supervisor 自有；复位零；采用与 `mtvec` 相同的模式与对齐规则 |
| `SCOUNTEREN` | `0x106` | `scounteren.CY/TM/IR/HPMn` | 向 U 开放计数读取 | 自有；复位零；保留基本三位与活动 HPM 位；两级许可见 03 |
| `SENVCFG` | `0x10a` | `senvcfg.FIOM` | U 执行环境配置 | 自有；复位零；仅叶模块 VM 开启时 bit 0 可写；其余位零 |
| `SSCRATCH` | `0x140` | `sscratch[31:0]` | S 软件上下文临时保存 | 自有；全 32 位读写；复位零；陷阱不自动覆盖 |
| `SEPC` | `0x141` | `sepc[31:0]` | S 陷阱返回地址 | 自有；复位零；软件及合法 S 陷阱写都清 `[1:0]` |
| `SCAUSE` | `0x142` | `scause.Interrupt/Exception Code` | S 陷阱原因 | 自有；复位零；保存 bit 31 和 `[4:0]`，其余位零 |
| `STVAL` | `0x143` | `stval[31:0]` | S 陷阱辅助信息 | 自有；全字读写；复位零；陷阱记录 `NextXtvalM` |
| `SIP` | `0x144` | `sip` | S 中断 pending 视图 | 转发到 Interrupt；共享 `mip`，受委托掩码限制；软件状态复位零；修改基值排除外部输入 |
| `STIMECMP` | `0x14d` | `stimecmp[31:0]` | S 计时比较值低半 | 仅 Sstc 开启时存在；自有低 32 位；全字读写，复位零 |
| `STIMECMPH` | `0x15d` | RV32 `stimecmp[63:32]` | S 计时比较值高半 | 仅 Sstc 开启时存在；自有高 32 位；全字读写，复位零 |
| `SATP` | `0x180` | `satp.MODE/ASID/PPN` | S 地址转换与保护配置 | 自有；复位零；S 的访问另受 TVM 限制；集成顶层固定 Bare；独立叶 VM 参数只允许保存字段 |

M 对 `satp` 不受 `mstatus.TVM` 限制；S 在 TVM=1 时读写该地址均被拒绝。
M 对 Sstc 比较地址不受 STCE/TM 限制；S 读写都要求 `menvcfg.STCE=1` 且 `mcounteren.TM=1`。
U 不能直接访问这些 S 地址。`stimecmp` 高低半拼成 64 位无符号数，原始比较为
`TIME_REGW >= stimecmp`；STIP 的实际来源选择在 Interrupt 中处理。
Sstc 的寄存器和 STCE 关系见 [官方 Sstc 定义](https://docs.riscv.org/reference/isa/v20260120/priv/sstc.html)。

## 陷阱字段的架构位置与本项目格式

这里的 `x` 分别取 `m`、`s`，对应上表的两组寄存器。
字段常量定义在 [PrivilegedConstants.scala](../../src/main/scala/riscvpipeline/privileged/PrivilegedConstants.scala)，
实际入口计算和辅助值选择在 [CSR.scala](../../src/main/scala/riscvpipeline/privileged/CSR.scala)。

| 寄存器／字段 | RV32 架构位置 | 本项目保存或更新规则 |
| --- | --- | --- |
| `xtvec.MODE` | `[1:0]` | `TrapVector.MODE_WIDTH=2`；合法结果为 Direct=`00` 或 Vectored=`01` |
| `xtvec.BASE` | `[31:2]` | Direct 清低两位；向量模式清低六位后置 bit 0，因而基址 64 字节对齐 |
| `xepc` | `[31:0]` | `ALIGNMENT_BITS=2`；固定 IALIGN=32，写入与陷阱记录都使 `[1:0]=00` |
| `xcause.Interrupt` | bit `31` | 架构寄存器最高位；软件写取原字 bit 31，陷阱写取内部 `NextCauseM[5]` |
| `xcause.Exception Code` | 架构字段 `[30:0]` | 本实现只保存 `[4:0]`，`CAUSE_WIDTH=5`；`[30:5]` 清零 |
| `xscratch` | `[31:0]` | 软件全字读写；无硬件陷阱自动写入 |
| `xtval` | `[31:0]` | 软件全字读写；陷阱辅助值由汇总模块选择，叶模块直接记录 |

内部陷阱总线为 `TRAP_CAUSE_WIDTH=6`：`[5]` 中断标志加 `[4:0]` 原因编号。
`ExceptionCause.INTERRUPT_BIT=5` 描述内部总线位号，不能用作 `mcause/scause` 的架构中断位号。
例如内部 `0b100111` 记录成 CSR 值 `0x80000007`。
机器原因字段的架构定义见 [官方 mcause](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)，
监督字段定义见 [官方 Supervisor CSR](https://docs.riscv.org/reference/isa/v20260120/priv/supervisor.html)。

代码判断软件写入的 bit 0 来规范化向量模式：开启向量支持时，原 MODE=`00/10` 都变为 Direct，
原 MODE=`01/11` 都变为 Vectored；关闭向量支持时全部变为 Direct。
向量模式只对中断使用 `BASE + 4*cause`，同步异常仍使用 BASE；加法按 RV32 回绕。
64 字节向量对齐是当前沿用的实现选择，比通用的四字节基址对齐更严格。

陷阱记录优先软件写；机器陷阱写 EPC/Cause/Tval，S 陷阱还要求原模式为 U/S。
汇总模块对中断和 ECALL 选择 tval=0，对非法指令选择原指令字，
对指令访问错误、断点、指令页故障选择 PC，其余选择 `IEUAdrM`。
所有上述自有状态同步复位零；提交还要求有效且无停顿／冲刷，汇总层陷阱取消全局软件写。

## 环境配置与 satp 字段

| 常量／字段 | 架构位置 | 数值 | 实现含义 |
| --- | --- | --- | --- |
| `EnvironmentConfig.WIDTH` | 完整 `menvcfg` | `64` | RV32 低／高两地址各保存 32 位 |
| `EnvironmentConfig.FIOM_MASK` | `menvcfg[0]`、`senvcfg[0]` | `0x1` | FIOM 字段可写掩码；只在独立叶模块 VM 参数开启时保留 |
| `EnvironmentConfig.STCE_BIT` | `menvcfg[63]` | `63` | S 计时比较访问与 STIP 来源控制 |
| `EnvironmentConfig.STCE_HIGH_MASK` | `menvcfgh[31]` | `0x80000000` | 完整 bit 63 在 RV32 高半的掩码；Sstc 关闭时写后仍零 |
| `SupervisorTranslation.MODE_BIT` | RV32 `satp[31]` | `31` | MODE=0 为 Bare，MODE=1 为 Sv32 编码 |
| `SupervisorTranslation.ASID_LOW` | RV32 `satp[30:22]` 的最低位 | `22` | `asidBits` 从字段低位开始实现 |
| `SupervisorTranslation.ASID_MAX_BITS` | RV32 ASID 字段宽度 | `9` | 独立叶参数范围 `0..9` |
| `satp.PPN` | `[21:0]` | 22 位 | 根页表物理页号；字段值表示物理地址除以 4 KiB |
| `SupervisorTranslation.MODE_PPN_MASK` | bit 31 与 `[21:0]` | `0x803fffff` | 保留 MODE 与 PPN；另 OR 已配置低 ASID 位掩码 |

FIOM 的架构意义是设备 I/O 顺序要求同时隐含主存顺序要求；STCE 是 Sstc 计时比较使能。
字段定义见 [官方 menvcfg](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)
及 [官方 senvcfg/satp](https://docs.riscv.org/reference/isa/v20260120/priv/supervisor.html)。
本项目集成 [PrivilegedConfig](../../src/main/scala/riscvpipeline/privileged/PrivilegedConfig.scala)
没有 VM／ASID 参数，CSR 汇总沿用叶模块 `virtualMemorySupported=false`，故 satp 固定零、FIOM 固定零。
独立 CSRSupervisor 的 VM 选项只保存配置：MODE=1 保留掩码字段，MODE=0 把整个 satp 置零；
未实现 ASID 高位清零。该选项没有 MMU、TLB 或页表遍历器，不能据此声称整机实现 Sv32。

## 计数器银行与事件地址：基址加槽编号

完整译码和状态来自 [CSRCounters.scala](../../src/main/scala/riscvpipeline/privileged/CSRCounters.scala)。
计数编号 `n` 是 CSR 低五位，范围 `0..31`；`0=cycle`、`1=time`、`2=instret`、`3..31=HPM`。
银行基址是算术编码起点，不自动代表每个起点都具有同名寄存器。

| CSRAddresses 常量 | 值 | ISA 映射公式 | 地址类别与实际行为 |
| --- | --- | --- | --- |
| `MCOUNTER_LOW_BASE` | `0xb00` | `0xb00+n` | M 读写，64 位机器计数器低 `[31:0]`；排除 `n=1` |
| `MCOUNTER_HIGH_BASE` | `0xb80` | `0xb80+n` | M 读写，机器计数器高 `[63:32]`；排除 `n=1` |
| `COUNTER_LOW_BASE` | `0xc00` | `0xc00+n` | U 地址类别只读影子低半；实际访问受计数许可约束 |
| `COUNTER_HIGH_BASE` | `0xc80` | `0xc80+n` | RV32 只读影子高半；与低半共用同一编号和权限位 |
| `MHPMEVENT_BASE` | `0x320` | `0x320+n`，仅 `n=3..31` | 事件选择器地址算术基址；不是一个 `mhpmevent0` CSR |
| `MHPMEVENT_FIRST` | `0x323` | `mhpmevent3` | 合法事件地址范围下界，M 读写 |
| `MHPMEVENT_LAST` | `0x33f` | `mhpmevent31` | 合法事件地址范围上界，M 读写 |

`MHPMEVENT_BASE` 和 `MCOUNTINHIBIT` 数值相同，但语义不同。
实际地址 `0x320` 由 Machine 译码为 `mcountinhibit`；Counters 只认 `0x323..0x33f` 的事件选择器。
这里没有 `mhpmevent0/1/2` 寄存器，也不能把 `0x320` 的 inhibit 写解释成事件选择器写。

| `n` | 机器低／高地址及 ISA 名称 | 只读影子低／高地址及 ISA 名称 | 状态、配置与复位 |
| --- | --- | --- | --- |
| `0` | `0xb00 mcycle`／`0xb80 mcycleh` | `0xc00 cycle`／`0xc80 cycleh` | 同一份 64 位状态；复位零；CY 未禁止时每拍增长 |
| `1` | `0xb01`／`0xb81` 不存在 | `0xc01 time`／`0xc81 timeh` | 外部 `TIME_REGW` 的同一 64 位输入；本模块不增长、不复位、不写它 |
| `2` | `0xb02 minstret`／`0xb82 minstreth` | `0xc02 instret`／`0xc82 instreth` | 同一份 64 位状态；复位零；IR 未禁止且合法退休时增长 |
| `3..31` | `0xb03..0xb1f mhpmcounter3..31`／`0xb83..0xb9f mhpmcounter3h..31h` | `0xc03..0xc1f hpmcounter3..31`／`0xc83..0xc9f hpmcounter3h..31h` | 活动槽保存 64 位状态，复位零；不活动槽地址存在、读零、写忽略 |

`mcycle/cycle`、`minstret/instret`、机器 HPM／影子 HPM 都是同一状态的不同地址视图。
`time` 是外部时间视图；内存映射机器计时设备并不在 `0xb01/0xb81` 变成机器 CSR。
低／高半由地址 bit 7 区分；活动槽为 `n=3..hpmCounters+2`，参数范围 `0..29`。
活动机器计数器半字写保持另一半旧值，并优先于该计数器当拍的整个 64 位增长。
HPM 输出数组下标 `i=0..28` 对应槽 `n=i+3`。

活动事件选择器同样复位零，保存本平台定义的单事件编号 `0..32`：0 不计数，
编号 `k=1..32` 选择 `HPMEventsM[k-1]`。整字写入大于 32 时规范化为零。
不活动槽的 `mhpmeventn` 地址也存在、读零、写忽略；这与不存在地址不同。
事件编号不是 RISC-V 统一的事件语义，具体流水线事件来源见 [03](03-privileged-fields.md)。

Counters 共报告 155 个存在地址：机器低高半 `31×2=62`、只读影子 `32×2=64`、事件 `29`。
M 可访问所有这些存在地址；S/U 对影子的附加访问许可和 inhibit 的逐位区别见
[计数权限与事件字段](03-privileged-fields.md)。未激活 HPM 的许可位被控制寄存器掩码清零，
因此其地址虽存在，也不会自动对 S/U 开放。影子地址的任何有效写意图由 CSRAccess 判非法。

返回 [报告目录](README.md)；继续阅读 [特权字段](03-privileged-fields.md) 或
[配置与验证](04-configuration-and-validation.md)。
