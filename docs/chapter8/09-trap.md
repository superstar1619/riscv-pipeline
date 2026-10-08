# 第九阶段：Trap 异常、中断与委托仲裁

`riscvpipeline.privileged.Trap` 汇总 M 级故障和 CSR 中断状态，组合输出是否陷入、
cause 与监督模式委托。模块不保存 CSR、特权级或 PC；这些状态由 CSR 和 PrivMode
在同一提交沿更新。本阶段提供独立模块及验证，生产流水线连接由后续阶段完成。

源码：[Trap.scala](../../src/main/scala/riscvpipeline/privileged/Trap.scala)。
测试：[TrapSpec.scala](../../src/test/scala/riscvpipeline/privileged/TrapSpec.scala)。
生成：[GenerateTrap.scala](../../src/main/scala/riscvpipeline/GenerateTrap.scala)。

## 依据与适配

- 教材 §8.2.5，书页 350–357 / PDF 页 385–392：陷阱分类、精确陷阱、异常委托、
  中断生成；表 8.4 给出 cause 和优先级，图 8.10 给出全局中断许可。
- 本地 CVW `src/privileged/trap.sv`：参考 pending/local enable、M/S 全局许可、
  Committed 与 WFI 延迟、优先级和委托判断。
- [实施计划 Task 9](plans/2026-10-08-completion.md)：U/M 始终支持，S 可配置；
  本平台地址未对齐总是陷阱，不实现硬件跨边界访问。
- [工程 Chisel 规范](../chisel-coding-standard.md)：组合模块采用 RawModule，
  重要接口有明确类型与位宽，组合输出完整驱动，Scala 源码两空格、95 列。

相对于 CVW，独立接口增加确定的无事件输出和支持模式防守；异常请求也受 Valid、
reset 和 CommittedF 门控。只采用实现的中断位，不接入 HPTW 故障或 Zicclsm 配置。
reset 是高有效组合抑制信号，模块没有时钟、寄存器或复位沿。

## 配置与接口

`Trap(supervisorSupported: Boolean = true)`。U=0、S=1、M=3；编码 2 未实现。
关闭 S 时，模式 1 的实际陷阱请求被抑制。两种配置保留相同的 31 个端口。

| 输入 | 类型/位宽 | 用途 |
| --- | --- | --- |
| `reset` | Bool | 立即将所有输出清零 |
| `ValidM` | Bool | 当前 M 级指令/事件有效 |
| `CommittedM` | Bool | M 级操作不可重启，暂缓中断 |
| `CommittedF` | Bool | F 级事务尚不能取消，暂缓所有陷阱 |
| `wfiM`、`wfiW` | Bool，各 1 位 | WFI 的中断延迟和连续 WFI 边界 |
| `STATUS_MIE`、`STATUS_SIE` | Bool，各 1 位 | 当前全局中断许可 |
| `PrivilegeModeW` | UInt(2.W) | 当前模式 |
| `MIP_REGW`、`MIE_REGW`、`MIDELEG_REGW` | UInt(12.W)，各 12 位 | pending、局部许可、中断委托 |
| `MEDELEG_REGW` | UInt(16.W) | 异常委托 |
| `InstrMisalignedFaultM`、`InstrAccessFaultM`、`InstrPageFaultM` | Bool，各 1 位 | 取指故障 |
| `IllegalInstrFaultM`、`BreakpointFaultM`、`EcallFaultM` | Bool，各 1 位 | 指令异常 |
| `LoadMisalignedFaultM`、`StoreAmoMisalignedFaultM` | Bool，各 1 位 | 数据地址未对齐 |
| `LoadAccessFaultM`、`StoreAmoAccessFaultM` | Bool，各 1 位 | 数据访问故障 |
| `LoadPageFaultM`、`StoreAmoPageFaultM` | Bool，各 1 位 | 数据页故障 |

| 输出 | 类型/位宽 | 契约 |
| --- | --- | --- |
| `TrapM` | Bool | 有实际中断或异常请求 |
| `InterruptM` | Bool | 本次采用中断 |
| `ExceptionM` | Bool | 本次存在有效异常请求，可与 Interrupt 同时为 1 |
| `IntPendingM` | Bool | 任一支持的中断位同时 pending 且局部 enabled |
| `DelegateM` | Bool | 本次实际采用的陷阱委托给 S |
| `CauseM` | UInt(5.W) | 采用事件的 cause 编码，不含 xcause 最高位中断标志 |

## Pending、许可与提交门控

实现中断位为 11/3/7/9/1/5，分别对应 MEI/MSI/MTI/SEI/SSI/STI。
开启 S 的掩码为 `0xaaa`，关闭 S 为 `0x888`。其余位即使 MIP/MIE 都为 1 也不产生
pending 或陷阱。

```text
pending = MIP & MIE & 实现掩码
IntPendingM = !reset && pending != 0
```

IntPending 不检查 Valid、当前模式、全局 MIE/SIE、Committed 或 WFI。这使等待模块
能够观察局部 pending 并唤醒，即使全局中断尚未开放。

| 中断目标 | U 当前模式 | S 当前模式 | M 当前模式 |
| --- | --- | --- | --- |
| M，未委托 | 始终允许 | 始终允许 | 需要 STATUS_MIE |
| S，已委托 | 始终允许 | 需要 STATUS_SIE | 始终禁止 |

关闭 S 时忽略 MIDELEG，机器中断仍按 M 目标许可；监督位被实现掩码清除。
开启 S 时，即使直接把机器来源委托位置 1，也依目标权限选择：M 中不能向 S 降级。
实际 CSR 的可写委托位约束仍由 CSR 模块负责。

普通事件需要 `ValidM && !reset && 支持当前模式 && !CommittedF`。
中断还需 `!CommittedM && (!wfiM || wfiW)`。因此 CommittedM、首次 M 级 WFI
只延后中断，不屏蔽本条指令异常；CommittedF 则连异常一起暂缓。
连续 WFI 时 wfiM/wfiW 同为 1 允许中断，以保留 CVW 的边界语义。

## Cause 优先级与委托

中断优先于全部异常；先比较中断目标，再比较同目标来源。
M 目标组与 S 目标组内部均按 `11 → 3 → 7 → 9 → 1 → 5`。因此未委托的 STIE
能够优先于已委托的 SEIE，不能简单按中断位号排序。

异常顺序为：

```text
取指页故障12 → 取指访问1 → 非法指令2 → 取指未对齐0 → 断点3
→ ECALL(U8/S9/M11) → store/AMO未对齐6 → load未对齐4
→ store/AMO页故障15 → load页故障13 → store/AMO访问7 → load访问5
```

未对齐在这里始终产生陷阱，优先于数据页/访问故障。页故障输入为保留的仲裁接口；
本阶段不因此实现 MMU 或页表遍历。

ExceptionM 表示有效异常集合，InterruptM 表示最终采用中断；两个标志可同时有效。
同时有效时 CauseM 和 DelegateM 采用中断，CSR 用 InterruptM 设置 xcause 中断标志
并写 tval=0。不能仅依据 ExceptionM 判断最终采用的事件类型。

Delegate 只有在 TrapM=1、开启 S、当前模式为 U/S 且相应 DELEG 位为 1 时有效。
中断查 MIDELEG，异常查 MEDELEG；M 中的异常永远不降到 S。无实际事件时 Cause=0、
Delegate=0，即使原始故障、委托 CSR 或 pending 输入仍为 1。

## 可复现验证

```bash
./scripts/sbt-local.sh 'testOnly riscvpipeline.privileged.TrapSpec'
./scripts/sbt-local.sh 'runMain riscvpipeline.GenerateTrap generated/trap true'
./scripts/sbt-local.sh 'runMain riscvpipeline.GenerateTrap generated/trap-no-s false'
verilator --lint-only --top-module Trap generated/trap/Trap.v
verilator --lint-only --top-module Trap generated/trap-no-s/Trap.v
```

生成入口为 `GenerateTrap [targetDir] [supervisorSupported]`，参数默认
`generated/trap` 与 `true`；拒绝超过两个参数。

9 项测试包含所有单项异常与 U/S/M ECALL、所有异常成对优先级、12 个中断位的局部
enable/实现掩码、每个实现来源的模式与全局 enable/委托组合、全部非空中断集合配
五组委托模式、Committed/WFI 布尔矩阵、异常委托与不可降级、两配置共 1024 组
固定种子随机事件，以及实际 Verilog 的端口/位宽和无状态检查。
参考模型按候选事件目标权限与教材顺序排序，未调用生产代码助手；异常单项、成对、
中断集合和提交/WFI 矩阵使用明确期望值。

## 实际结果

2026-10-08，JDK17、sbt1.10.7、Scala2.13.14、Chisel3.6.1、chiseltest0.6.2：

| 检查 | 实际结果 |
| --- | --- |
| RED：真实测试对输出清零骨架 | 9 项中 8 项按缺失行为失败，1 项接口检查通过；退出码 1 |
| GREEN：TrapSpec | 9/9 通过，无取消或忽略；退出码 0 |
| RTL | S 开启/关闭两组生成成功，相同 31 端口，无生产时钟或状态 |
| Verilator 5.036 lint | 两组退出码 0，无警告或错误 |

日志位于忽略目录 `target/chapter8/completion/trap/`：`red.log`、`green.log`、
`generate.log`、`lint.log`、`lint-no-s.log`。保留现有 ChiselStage 弃用提示，未升级依赖。
本阶段按独立模块任务范围未重跑全套测试或 CoreMark；后续流水线阶段统一进行回归。

当前验证不包括 CSR 状态的提交、重定向 PC、流水冲刷或处理程序执行，这些由后续
CSR/Privileged/流水线集成阶段完成。模块不实现 MMU/PMP、CLINT/PLIC 本体或虚拟化，
lint 与仿真也不代表形式等价或物理时序收敛。按照连续推进计划，接着完成后续阶段。
