# 特权常量：字段含义、寄存器位与实现边界

本文逐项解释 [PrivilegedConstants.scala](../../../src/main/scala/riscvpipeline/privileged/PrivilegedConstants.scala)
的八个嵌套对象。表内名称省略 `PrivilegedConstants.<对象>.` 前缀；数值是 Scala
主机常量，使用处才构造 Chisel `UInt`。位号从最低位 0 开始，`[高:低]` 两端均包含。
同一数值可以是字段位置、原因编码、掩码或接口宽度，不能只按数字推断含义。
CSR 地址及完整寄存器目录见 [02](02-csr-register-map.md)，配置与验证见
[04](04-configuration-and-validation.md)。本文描述当前源码，不将叶模块可选字段等同于整机功能。

## 1. Privilege：模式编码

使用处：[PrivMode](../../../src/main/scala/riscvpipeline/privileged/PrivMode.scala)、
[CSRStatus](../../../src/main/scala/riscvpipeline/privileged/CSRStatus.scala)、
[Trap](../../../src/main/scala/riscvpipeline/privileged/Trap.scala)。

| 常量 | 值 | 寄存器/信号映射与含义 |
| --- | --- | --- |
| `MODE_WIDTH` | 2 | 当前模式 `PrivilegeModeW`、下一模式和 `mstatus.MPP[12:11]` 的宽度；当前模式自身不是独立 CSR |
| `MODE_U` | 0 / `00` | 用户级 U；MPP 可保存此编码，SPP=0 表示 U |
| `MODE_S` | 1 / `01` | 监督级 S；须配置支持 S，SPP=1 表示 S |
| `MODE_RESERVED` | 2 / `10` | 保留编码；当前项目不接受为运行模式或有效 MPP 写值 |
| `MODE_M` | 3 / `11` | 机器级 M；`PrivMode` 复位进入此模式，MPP 可保存此编码 |

`mstatus` 复位零使 MPP=U，并不使当前 CPU 模式变成 U。非法或未支持的 MPP
软件写只保留旧 MPP，其他合法状态字段仍更新。SRET 从一位 SPP 组合出 U/S；
MRET 从两位 MPP 取返回模式。模式与中断栈的架构关系核对自
[Machine-Level ISA：状态与陷阱返回](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)。

## 2. ExceptionCause：异常原因与内部陷阱编码

使用处：[Trap](../../../src/main/scala/riscvpipeline/privileged/Trap.scala)、
[CSR](../../../src/main/scala/riscvpipeline/privileged/CSR.scala)、
[CSRMachine](../../../src/main/scala/riscvpipeline/privileged/CSRMachine.scala)、
[CSRSupervisor](../../../src/main/scala/riscvpipeline/privileged/CSRSupervisor.scala)。

| 常量 | 值 | 精确用途 |
| --- | --- | --- |
| `CAUSE_WIDTH` | 5 | `CauseM[4:0]` 是原因编号；硬件写 `mcause/scause` 时放入寄存器 `[4:0]` |
| `TRAP_CAUSE_WIDTH` | 6 | 叶模块 `NextCauseM[5:0]`，即内部 `{InterruptM, CauseM[4:0]}` |
| `INTERRUPT_BIT` | 5 | **内部** `NextCauseM[5]`；记录到 RV32 `mcause/scause.Interrupt[31]`，不是架构寄存器 bit 5 |
| `DELEGATION_WIDTH` | 16 | 内部 `MEDELEG_REGW[15:0]`；CSR 读取时零扩展到 32 位，仅支持这部分异常委托槽 |
| `DELEGATION_INDEX_WIDTH` | 4 | `Trap` 用 `CauseM[3:0]` 索引当前委托总线；这里产生的原因均在 0–15 内 |

软件写 `mcause/scause` 同样只保存 bit 31 和 `[4:0]`，`[30:5]` 为零。
异常与中断共享数值空间，必须结合 Interrupt 标志解释。例如编号 5：标志为 0 是
load access fault，标志为 1 是 supervisor timer interrupt。当前工程未实现更宽原因集合。
原因及最高位标志可对照
[Machine-Level ISA：mcause](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)、
[Supervisor-Level ISA：scause](https://docs.riscv.org/reference/isa/v20260120/priv/supervisor.html)。

以下表格均指 Interrupt=0；对应 `medeleg[n]` 中 n 与异常原因数值相同。

| 常量 | 原因值 n | `mcause/scause` 异常含义 | `Trap` 来源 |
| --- | --- | --- | --- |
| `INSTRUCTION_MISALIGNED` | 0 | 指令地址未对齐 | `InstrMisalignedFaultM` |
| `INSTRUCTION_ACCESS` | 1 | 指令访问故障 | `InstrAccessFaultM` |
| `ILLEGAL_INSTRUCTION` | 2 | 非法指令 | `IllegalInstrFaultM` |
| `BREAKPOINT` | 3 | 断点异常，包括 EBREAK | `BreakpointFaultM` |
| `LOAD_MISALIGNED` | 4 | load 地址未对齐 | `LoadMisalignedFaultM` |
| `LOAD_ACCESS` | 5 | load 访问故障 | `LoadAccessFaultM` |
| `STORE_AMO_MISALIGNED` | 6 | store/AMO 地址未对齐 | `StoreAmoMisalignedFaultM` |
| `STORE_AMO_ACCESS` | 7 | store/AMO 访问故障 | `StoreAmoAccessFaultM` |
| `ECALL_U` | 8 | 来自 U 的环境调用 | `EcallFaultM`，计算 `8 + PrivilegeModeW` |
| `ECALL_S` | 9 | 来自 S 的环境调用 | 同上，S=1，故结果为 9 |
| `ECALL_M` | 11 | mcause：来自 M 的环境调用；标准 scause 的异常编号 11 为保留 | 同上，M=3，故结果为 11，不委托到 S |
| `INSTRUCTION_PAGE` | 12 | 指令页故障 | `InstrPageFaultM` |
| `LOAD_PAGE` | 13 | load 页故障 | `LoadPageFaultM` |
| `STORE_AMO_PAGE` | 15 | store/AMO 页故障 | `StoreAmoPageFaultM` |

`STORE_AMO_*` 保留架构类别名称；当前 RV32I 流水线的 store 路径不因此支持 A 扩展。
`Trap` 同时出现异常时按源码顺序选取：指令页故障、指令访问、非法指令、指令未对齐、
断点、ECALL、store 未对齐、load 未对齐、store 页故障、load 页故障、store 访问、load 访问。
可接受中断同拍出现时优先于异常。这是本模块的仲裁行为，不应从原因数值大小推导优先级。

| 委托掩码常量 | 值 | 全部置 1 的位与源码作用 |
| --- | --- | --- |
| `SUPERVISOR_MASK` | `0x03ff` | `medeleg[0..9]`：包括上述未对齐、访问、非法、断点和 U/S ECALL；支持 S 且 VM 关闭时采用 |
| `VIRTUAL_MEMORY_MASK` | `0xb3ff` | `medeleg[0..9]` 加 `[12]、[13]、[15]`，即 `0x03ff \| 0x1000 \| 0x2000 \| 0x8000`；叶模块 VM 开启时采用 |

关闭 S 时 `CSRMachine` 的委托写掩码为零，且不报告 `medeleg/mideleg` 地址存在。
上述置位槽均有对应异常；bit 11 的 M ECALL 明确排除。10、14 未进入掩码，在本项目
无异常源，其中 10 在其他扩展上下文可能另有定义，不能笼统称为所有 ISA 下的保留位。
掩码表示可保存的委托位，只有实际产生异常才起作用：当前 `PipelineBase` 的三类页故障
输入固定为 false。`BreakpointFaultM` 的该输入也固定 false，EBREAK 的译码异常由特权路径处理。
来源为 M 时始终不委托；U/S 来源还须有实际陷阱并检查对应委托位。
官方当前规范包含更宽 `medeleg` 及扩展原因，本项目 16 位内部表示和地址集合只覆盖此子集。
参见 [Machine-Level ISA：异常与中断委托](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)。

## 3. Interrupt：pending、enable、delegation 共用的位索引

使用处：[CSRInterrupt](../../../src/main/scala/riscvpipeline/privileged/CSRInterrupt.scala)、
[CSRMachine](../../../src/main/scala/riscvpipeline/privileged/CSRMachine.scala)、[Trap](../../../src/main/scala/riscvpipeline/privileged/Trap.scala)。
`WIDTH=12` 仅表示内部总线 `[11:0]`；CSR 软件视图是零扩展后的 RV32 32 位。

| 位常量 | 值/中断 cause | `mip` pending 字段 | `mie` enable 字段 | `mideleg` 与 S 视图 |
| --- | --- | --- | --- | --- |
| `SSIP_BIT` | 1 | SSIP，监督软件中断待处理 | SSIE | `mideleg[1]` 可写；委托后 `sip.SSIP[1]` / `sie.SSIE[1]` 可见 |
| `MSIP_BIT` | 3 | MSIP，机器软件中断待处理 | MSIE | `[3]` 本项目不允许委托，无 S 视图 |
| `STIP_BIT` | 5 | STIP，监督定时器中断待处理 | STIE | `mideleg[5]` 可写；委托后 `sip.STIP[5]` / `sie.STIE[5]` 可见 |
| `MTIP_BIT` | 7 | MTIP，机器定时器中断待处理 | MTIE | `[7]` 本项目不允许委托，无 S 视图 |
| `SEIP_BIT` | 9 | SEIP，监督外部中断待处理 | SEIE | `mideleg[9]` 可写；委托后 `sip.SEIP[9]` / `sie.SEIE[9]` 可见 |
| `MEIP_BIT` | 11 | MEIP，机器外部中断待处理 | MEIE | `[11]` 本项目不允许委托，无 S 视图 |

表中 cause 值在 Interrupt=1 时解释；标准 scause 中只有对应的 S 类中断编号，
机器中断编号 3/7/11 不因软件可以写入低五位就变成标准 S 原因。
同一位索引有三种角色：`mip` 表示请求、`mie` 表示局部许可、`mideleg` 表示目标为 S。
`sie/sip` 共享底层状态，未委托位读零。它们也不是 `mstatus.MIE/SIE` 的全局许可位。
架构对应关系核对自
[Machine-Level ISA：mip/mie](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)、
[Supervisor-Level ISA：sip/sie](https://docs.riscv.org/reference/isa/v20260120/priv/supervisor.html)。

| 常量 | 值 | 位分解及具体用途 |
| --- | --- | --- |
| `SUPERVISOR_MASK` | `0x222` | `{1,5,9}`，SSIP/STIP/SEIP；S 位、`mideleg` 可写位、通常的 MIP 软件写位 |
| `MACHINE_MASK` | `0x888` | `{3,7,11}`，MSIP/MTIP/MEIP；关闭 S 时仍支持的中断 |
| `SUPPORTED_MASK` | `0xaaa` | `{1,3,5,7,9,11}`，即两组掩码之或；开启 S 时 MIE 写掩码与 Trap 支持掩码 |
| `SSIP_MASK` | `0x002` | `{1}`；SIP 软件写只更新当前已委托的 SSIP |
| `STCE_SOFTWARE_MASK` | `0x202` | `{1,9}`；Sstc 且 STCE=1 时 MIP 软件写排除 STIP |
| `PRIORITY` | `Seq(11,3,7,9,1,5)` | MEI、MSI、MTI、SEI、SSI、STI；先扫全部 M 目标，再扫全部 S 目标，每组采用此顺序 |

例如未委托 STIP 的 M 目标可先于已委托 SEIP 的 S 目标；不能把 `PRIORITY`
误写成跨所有目标只扫一遍。全局许可还取决于当前模式，较高目标模式不受当前模式的 xIE 限制。
内部 bit 0/2/4/6/8/10 均无本项目中断源，固定零；扩展中断未纳入本模块。

MIP 中 MSIP/MTIP/MEIP 来自 `MSwInt/MTimerInt/MExtInt`，CSR 软件写不能修改；
SSIP 来自内部软件状态；SEIP 读值是内部软件位与 `SExtInt` 的或。STIP 在 Sstc
关闭或 STCE=0 时来自软件位，Sstc 且 STCE=1 时来自 `STimerInt` 比较信号。
此时一次整字 MIP 写会清除潜藏的软件 STIP；SIP 写始终只更新已委托 SSIP。

`MIP_REGW/SIP_REGW` 是软件读值，包含硬件电平；`*_REGW_writeable` 是 CSRRS/CSRRC
修改用的内部软件基值，不是写权限掩码。比如外部 SEIP=1 时，读取必须返回 1，
但修改其他位不能把该电平写回软件 SEIP 使其粘住。此行为与
[官方 mip.SEIP 读改写说明](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)一致。

## 4. StatusField：mstatus、sstatus 与 RV32 mstatush

使用处：[CSRStatus](../../../src/main/scala/riscvpipeline/privileged/CSRStatus.scala)。`sstatus`
是 `mstatus` 的受限共享视图，写 S 字段会改变同名 M 状态；没有第二份状态或 `sstatush`。
下面“可写”还须满足提交门控、地址授权和配置；未支持字段固定零。

| 位/宽度常量 | 值 | 精确字段 | 意义与当前保存条件 |
| --- | --- | --- | --- |
| `SIE_BIT` | 1 | `mstatus/sstatus.SIE[1]` | S 全局中断许可；支持 S 时保存 |
| `MIE_BIT` | 3 | `mstatus.MIE[3]` | M 全局中断许可；始终保存 |
| `SPIE_BIT` | 5 | `mstatus/sstatus.SPIE[5]` | 陷阱前 SIE；S 陷阱/返回更新，支持 S 时保存 |
| `UBE_BIT` | 6 | `mstatus/sstatus.UBE[6]` | U 数据访问端序，1 为大端；大端配置启用时保存，S 关闭时仅 mstatus 可见 |
| `MPIE_BIT` | 7 | `mstatus.MPIE[7]` | 陷阱前 MIE；M 陷阱/返回更新 |
| `SPP_BIT` | 8 | `mstatus/sstatus.SPP[8]` | S 陷阱前模式，0=U、1=S；支持 S 时保存 |
| `MPP_LOW`、`MPP_HIGH` | 11、12 | `mstatus.MPP[12:11]` | M 陷阱前模式，编码见 Privilege；非法软件值保留旧字段 |
| `MPRV_BIT` | 17 | `mstatus.MPRV[17]` | M 数据访问采用 MPP 指定的有效模式；返回到非 M 时清零 |
| `SUM_BIT` | 18 | `mstatus/sstatus.SUM[18]` | 架构上许可 S 数据访问 U 页；仅叶模块 VM 开启时保存 |
| `MXR_BIT` | 19 | `mstatus/sstatus.MXR[19]` | 架构上可将可执行页视为可读；支持 S 时保存，当前无 MMU 权限效果 |
| `TVM_BIT` | 20 | `mstatus.TVM[20]` | 限制 S 访问 satp/相关虚存操作；支持 S 时保存，CSR 检查实际使用此位 |
| `TW_BIT` | 21 | `mstatus.TW[21]` | 限制低特权 WFI；始终保存，特权译码/WFI 路径消费 |
| `TSR_BIT` | 22 | `mstatus.TSR[22]` | 限制 S 执行 SRET；支持 S 时保存；M 执行 SRET 不受此位限制 |
| `SBE_HIGH_BIT` | 4 | RV32 `mstatush.SBE[4]`，对应 RV64 `mstatus.SBE[36]` | S 数据访问端序；大端且支持 S 时保存 |
| `MBE_HIGH_BIT` | 5 | RV32 `mstatush.MBE[5]`，对应 RV64 `mstatus.MBE[37]` | M 数据访问端序；大端配置启用时保存 |
| `FS_WIDTH` | 2 | `mstatus/sstatus.FS[14:13]` 的字段宽度 | 浮点上下文状态字段；本项目 `STATUS_FS=00`，没有可写 FS 状态 |

未实现的 VS `[10:9]`、XS `[16:15]`、SD `[31]` 及其他未纳入掩码的字段读零。
端序位不改变指令取指的小端规则。当前有效数据模式选择顺序是：叶 VM 且 HPTW 选中
时用 S；否则当前 M 且 MPRV=1 时用 MPP；否则用当前模式，再选择 UBE/SBE/MBE。
字段位置和共享视图核对自
[Machine-Level ISA：mstatus/mstatush](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)、
[Supervisor-Level ISA：sstatus](https://docs.riscv.org/reference/isa/v20260120/priv/supervisor.html)。

| 掩码常量 | 值 | 全部置位字段及用途 |
| --- | --- | --- |
| `MPP_MASK` | `0x00001800` | bits 11、12；软件整字写对 MPP 单独作 WARL 处理 |
| `SUPERVISOR_BASE_MASK` | `0x00080122` | SIE[1]、SPIE[5]、SPP[8]、MXR[19] |
| `MACHINE_BASE_MASK` | `0x00221888` | MIE[3]、MPIE[7]、MPP[12:11]、MPRV[17]、TW[21] |
| `SUPERVISOR_CONTROL_MASK` | `0x00500000` | TVM[20]、TSR[22]；支持 S 时并入 mstatus |
| `SUM_MASK` | `0x00040000` | SUM[18]；叶 VM 时并入 S 共享字段 |
| `UBE_MASK` | `0x00000040` | UBE[6]；大端配置时并入低半部字段 |
| `SBE_HIGH_MASK` | `0x10` | mstatush[4]；大端且支持 S 时可写 |
| `MBE_HIGH_MASK` | `0x20` | mstatush[5]；大端时可写 |
| `MACHINE_STACK_MASK` | `0x00001888` | MIE[3]、MPIE[7]、MPP[12:11]；M 陷阱/返回只替换这些栈位 |
| `SUPERVISOR_STACK_MASK` | `0x00000122` | SIE[1]、SPIE[5]、SPP[8]；S 陷阱只替换这些栈位 |
| `SUPERVISOR_RETURN_MASK` | `0x00020122` | 上述 S 栈位加 MPRV[17]；SRET 同时清 MPRV |
| `MPRV_CLEAR_MASK` | `0xfffdffff` | 32 位全 1 除 bit 17 为 0；MRET 返回非 M 时与此掩码相与 |

掩码并不全部表示软件权限：`*_STACK_MASK` 用于硬件替换字段，`MPRV_CLEAR_MASK`
用于保持其他字段并清位。M 陷阱令 MPIE=旧 MIE、MIE=0、MPP=来源模式；
S 陷阱令 SPIE=旧 SIE、SIE=0、SPP=来源是否为 S。返回恢复 xIE、令 xPIE=1，
并把 xPP 置为最低支持 U。整个 `mstatush` 在这些事件中保持，不随低半软件写改变。

令 S/VM/BE 分别表示支持监督、叶虚存字段、大端配置，则源码生成的掩码为：

```text
S_MASK = S ? (0x80122 | (VM ? 0x40000 : 0) | (BE ? 0x40 : 0)) : 0
M_MASK = 0x221888 | S_MASK | (S ? 0x500000 : 0) | (BE ? 0x40 : 0)
H_MASK = BE ? (0x20 | (S ? 0x10 : 0)) : 0
```

独立 `CSRStatus` 默认 S=true、VM=false、BE=false 时为 `S_MASK=0x80122`、`M_MASK=0x7a19aa`、
`H_MASK=0`；S/VM/BE 均启用时为 `0xc0162`、`0x7e19ea`、`0x30`。
这些是字段上限，非法 MPP 仍保留旧值。当前 CSR 汇总不启用叶 VM，HPTW 选择固定 false。

## 5. Counter：64 位状态、访问许可与平台事件

使用处：[CSRCounters](../../../src/main/scala/riscvpipeline/privileged/CSRCounters.scala)、
[CSRMachine](../../../src/main/scala/riscvpipeline/privileged/CSRMachine.scala)、
[CSRSupervisor](../../../src/main/scala/riscvpipeline/privileged/CSRSupervisor.scala)、
[PipelineBase](../../../src/main/scala/riscvpipeline/PipelineBase.scala)。

| 常量 | 值 | 字段/地址映射和用途 |
| --- | --- | --- |
| `WIDTH` | 64 | mcycle/minstret/活动 HPM 状态和外部 TIME_REGW 的宽度；RV32 每次读写 32 位半部 |
| `CYCLE` | 0 | 计数编号及 `mcounteren/scounteren.CY[0]`、`mcountinhibit.CY[0]` |
| `TIME` | 1 | time 编号及 `mcounteren/scounteren.TM[1]`；`mcountinhibit[1]` 无有效禁止字段 |
| `INSTRET` | 2 | instret 编号及 `mcounteren/scounteren.IR[2]`、`mcountinhibit.IR[2]` |
| `HPM_FIRST` | 3 | 首个 HPM 编号，控制寄存器的 HPM3[3]，计数器与事件槽从 3 开始 |
| `HPM_COUNT` | 29 | 编号 3..31，共 29 槽；数组下标 i 对应编号 i+3 |
| `INDEX_WIDTH` | 5 | CSR 地址 `[4:0]` 为计数/事件编号；也作为控制位索引 |
| `BANK_WIDTH` | 7 | `CSRAddresses.ADDRESS_WIDTH(12)-5`，CSR 地址 `[11:5]` 的分组宽度 |
| `HIGH_HALF_BIT` | 7 | **CSR 地址** bit 7 区分计数低/高半，不是计数值 bit 7；如 B00/B80、C00/C80 |
| `EVENT_COUNT` | 32 | HPMEventsM[31:0] 输入共 32 个平台事件；编号 1..32 选择相应输入位 |
| `EVENT_SELECTOR_WIDTH` | 6 | mhpmeventn 的内部选择器 `[5:0]`，足以保存 0..32；CSR 读值零扩展为 32 位 |
| `BASE_ENABLE_MASK` | `7` / `0x7` | bits 0、1、2，mcounteren/scounteren 基础 CY/TM/IR 可写位 |
| `TIME_MASK` | `2` / `0x2` | bit 1；从 mcountinhibit 写掩码中排除 time |

有 k 个活动 HPM 时，`COUNTER_MASK = 0x7 | (((1 << k)-1) << 3)`；
mcounteren/scounteren 使用此掩码，mcountinhibit 使用 `COUNTER_MASK & ~0x2`。
M 读计数不受 counteren 限制；S 读影子需 mcounteren[n]=1；有 S 的 U 还需
scounteren[n]=1，无 S 的 U 只检查 mcounteren。许可关闭不停止增长，inhibit=1
才禁止对应自有计数器增长；time 由外部提供且不受 inhibit 控制。核对依据：
[Machine-Level ISA：性能计数、counteren、countinhibit](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)。

低半 CSR 读写完整值 `[31:0]`，高半 CSR 读写 `[63:32]`；软件半字写保持另一半，
并覆盖该计数器同拍的整个增长。未活动 HPM 槽地址仍存在，读零、写无状态效果。
事件编号 0 不计数，1..32 选输入 bit 编号减一；写入整字大于 32 时选择器归零。
事件写入当拍增长使用旧选择器。标准并未为下面这些平台事件预定义固定编号。

| 事件位常量 | `HPMEventsM` 位 | 软件选择器值 | 当前 PipelineBase 实际来源与含义 |
| --- | --- | --- | --- |
| `EVENT_RETIRED` | 0 | 1 | `InstrRetiredM`，有效、非陷阱、非停顿、非复位的退休候选 |
| `EVENT_LOAD` | 1 | 2 | `ieu.io.MemRWM(MEM_READ_BIT)`，M 级读取请求位；未另外用退休信号资格化 |
| `EVENT_STORE` | 2 | 3 | `ieu.io.MemRWM(MEM_WRITE_BIT)`，M 级写入请求位；未另外用退休信号资格化 |
| `EVENT_LOAD_STALL` | 3 | 4 | `ieu.io.LoadStallD`，D 级真实源依赖 E 级 load 或 CSR 晚结果时的停顿条件 |
| `EVENT_STORE_STALL` | 4 | 5 | `ieu.io.StoreStallD`，E 级 store 与 D 级 load 的共享 DTIM 结构冲突停顿条件 |

其余事件输入 bit 5..31 当前为零。load/store 常量名称不能解释成“已退休 load/store
数”，也不能解释为“总线成功接受事务数”。HPM 模块只采样选中输入，每拍最多加一，
不会统一再套 Valid/Trap 门控；脉冲含义与资格化由调用方决定。
这两个冒险条件在 E 级重定向优先解除实际 D/F 停顿时仍可能为真，因此事件也不等同于最终停顿周期数。

## 6. TrapVector：入口字段与对齐

| 常量 | 值 | 精确字段/用途 |
| --- | --- | --- |
| `ALIGNMENT_BITS` | 2 | mtvec/stvec.MODE[1:0] 宽度边界，BASE 在 [31:2]；直接入口清低两位，mepc/sepc 写入也清 [1:0]，对应本项目 IALIGN=32 |
| `MODE_WIDTH` | 2 | mtvec/stvec.MODE[1:0] 的读取/比较宽度 |
| `MODE_VECTORED` | 1 / `01` | MODE=1；中断目标为 BASE+4×cause，异常仍到 BASE |
| `VECTOR_ALIGNMENT_BITS` | 6 | 本实现向量 BASE 清 [5:0]，64 字节对齐；不是所有 RISC-V 实现统一要求 |

使用处是 CSRMachine/CSRSupervisor 的 WARL 写和 CSR 的地址计算。关闭向量配置时
所有写值转为 Direct；开启时用写值 bit 0 选择：输入 MODE=0/2 转 Direct，1/3 转 Vectored，
保存值只可能 00/01。向量模式保存 `[31:6]`、清 `[5:1]`、置 bit 0。跳转偏移左移
2 位，32 位相加回绕。架构的 Direct/Vectored 语义与可更严格对齐规则见
[Machine-Level ISA：mtvec](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)。

## 7. EnvironmentConfig：FIOM 与 Sstc STCE

| 常量 | 值 | 精确字段/用途 |
| --- | --- | --- |
| `WIDTH` | 64 | 完整 menvcfg 输出宽度，由 `{EnvHigh[31:0], EnvLow[31:0]}` 拼接；senvcfg 仍为 RV32 32 位 |
| `FIOM_MASK` | `1` / `0x1` | menvcfg.FIOM[0]、senvcfg.FIOM[0]；仅叶 VM 配置时允许保存 |
| `STCE_BIT` | 63 | 完整 menvcfg.STCE[63]；CSR 汇总据此控制 timer 权限及 STIP 来源 |
| `STCE_HIGH_MASK` | `0x80000000` | RV32 menvcfgh.STCE[31]，即完整 64 位 menvcfg[63]；Sstc 配置时可写 |

FIOM 的架构含义是让设备 I/O 排序要求同时涵盖内存；menvcfg 面向低于 M 的模式，
senvcfg 面向 U。本项目只保存字段，未实现由此产生的 FENCE/原子排序效果。
当前汇总 VM=false，FIOM 读零。参见
[Machine-Level ISA：menvcfg](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)、
[Supervisor-Level ISA：senvcfg](https://docs.riscv.org/reference/isa/v20260120/priv/supervisor.html)。

Sstc 关闭时 STCE=0，stimecmp/stimecmph 地址不存在；开启后 M 访问不受 STCE/TM
限制，S 读写都要求 `menvcfg.STCE=1 && mcounteren.TM=1`。原始定时器比较为无符号
`TIME_REGW >= STIMECMP_REGW`，与 STCE 门控分开；CSRInterrupt 再决定 STIP 来源。
STCE 不等于中断 enable，也不等于委托位，比较置 pending 后还要经过这些许可条件。
扩展与字段关系见 [Sstc 1.0](https://docs.riscv.org/reference/isa/v20260120/priv/sstc.html)，
当前权限核对自 [Machine-Level ISA：STCE 与 TM](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)。

## 8. SupervisorTranslation：satp 的 RV32 字段保存

| 常量 | 值 | 精确字段/用途 |
| --- | --- | --- |
| `ASID_MAX_BITS` | 9 | RV32 satp.ASID[30:22] 最多 9 位；叶参数 asidBits 范围 0..9 |
| `ASID_LOW` | 22 | ASID 字段最低位，软件 ASID 的 bit 0 对应 satp[22] |
| `MODE_BIT` | 31 | satp.MODE[31]：0=Bare，1=Sv32 的架构编码 |
| `MODE_PPN_MASK` | `0x803fffff` | bit 31 加 bits 21..0，即 MODE 和 PPN[21:0]；不包含 ASID |

CSRSupervisor 的保存掩码为 `0x803fffff | (((1 << asidBits)-1) << 22)`，
因此只实现 ASID 的低位，未配置 ASID 位读零。VM 开启时 MODE=1 保存掩码后的字；
MODE=0 把整个 satp 清零，不保存 Bare 下的非零剩余字段。VM 关闭时 satp 固定零，
地址在支持 S 时仍存在。S 访问 satp 还需 TVM=0；M 访问不受 TVM 限制。
布局、ASID 低位优先及 Bare/Sv32 编码核对自
[Supervisor-Level ISA：satp](https://docs.riscv.org/reference/isa/v20260120/priv/supervisor.html)。

这些常量只定义 CSR 保存布局。叶模块不执行页表遍历、地址翻译或页权限检查；当前
CSR 汇总保持 VM=false、asidBits=0，页故障输入固定零，不能据 `MODE_BIT=31`
或 `VIRTUAL_MEMORY_MASK` 宣称整机已有 Sv32/MMU。相关配置限制继续见 [04](04-configuration-and-validation.md)。
