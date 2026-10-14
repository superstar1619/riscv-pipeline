# 常量命名重构与 RISC-V 字段对照报告

记录日期：2026-10-11。对象为本仓库当前 RV32 五级流水线及特权单元。
本次将分散的指令编码、CSR 地址、状态位、掩码和控制值集中为有名称的 Scala 常量，
并整理译码和读表结构。原有硬件功能、接口、时序及配置默认值保持不变。
各表同时解释“标准规定的字段”与“代码实际支持的行为”，方便从源码定位到指令集定义。

## 阅读目录

| 文档 | 查阅内容 |
| --- | --- |
| [01 指令与内部控制](01-instructions-and-controls.md) | 指令位段、opcode/funct3/funct7、立即数、CSR 六种指令、SYSTEM、13 位内部控制字、前递与访存控制 |
| [02 CSR 寄存器地址](02-csr-register-map.md) | 所有命名 CSR 地址、权限编码、M/S 寄存器、标识寄存器、计数器银行、读写与复位行为 |
| [03 特权字段](03-privileged-fields.md) | mstatus/mstatush/sstatus 位段、中断和委托、异常原因、计数许可、HPM 事件、向量与环境配置 |
| [04 配置与验证](04-configuration-and-validation.md) | 配置参数默认值和约束、对应寄存器字段、外部输入、生成入口、验证命令与实际结果 |

例如，查 `0xf15`：在 02 中找到 `CSRAddresses.MCONFIGPTR`，对应只读机器 CSR
`mconfigptr`，表示配置数据结构的物理地址指针；本项目固定读零。
查 `StatusField.MIE_BIT`：在 03 中找到 bit 3，它对应 `mstatus.MIE` 全局机器中断使能，
与 `mie` 寄存器里的逐源中断使能不同。

## 常量分类与源码位置

| 源码入口 | 维护的内容 | 是否可由生成参数改变 |
| --- | --- | --- |
| [Architecture.scala](../../../src/main/scala/riscvpipeline/isa/Architecture.scala) | RV32 位宽、字节/指令大小、寄存器数及指令字段宽度 | 当前核心固定 RV32；更改常量不能自动获得 RV64 |
| [InstructionEncoding.scala](../../../src/main/scala/riscvpipeline/isa/InstructionEncoding.scala) | ISA opcode、funct3/funct7、完整 SYSTEM 指令和 NOP | 标准编码，不能随意配置 |
| [InstructionDecode.scala](../../../src/main/scala/riscvpipeline/isa/InstructionDecode.scala) | CSR 和 FENCE 的共用识别方法 | 方法按固定编码判断 |
| [ControlEncoding.scala](../../../src/main/scala/riscvpipeline/control/ControlEncoding.scala) | ALU、扩展器、结果选择、前递、访存内部编码 | 仅供内部模块协议使用，不是 ISA 字段 |
| [CSRAddresses.scala](../../../src/main/scala/riscvpipeline/privileged/CSRAddresses.scala) | CSR 地址、地址属性位段和计数器银行基址 | 地址固定；配置可决定是否实现某地址 |
| [PrivilegedConstants.scala](../../../src/main/scala/riscvpipeline/privileged/PrivilegedConstants.scala) | 特权模式、原因、状态/中断/计数位号、字段掩码 | 编码固定；配置可决定哪些位可写或可见 |
| [CpuConfig.scala](../../../src/main/scala/riscvpipeline/config/CpuConfig.scala)、[PrivilegedConfig.scala](../../../src/main/scala/riscvpipeline/privileged/PrivilegedConfig.scala) | 容量、复位向量、S/Sstc、端序、HPM 等生成配置 | Scala 生成阶段可配置，具有明确约束 |
| [WFI.scala](../../../src/main/scala/riscvpipeline/privileged/WFI.scala) | 默认及最大等待阈值 | `wfiTimeoutCycles` 可配置；不是 ISA 固定周期数 |
| [TestEncoding.scala](../../../src/test/scala/riscvpipeline/TestEncoding.scala) | 测试使用的独立编码、字段与预期掩码 | 保持测试侧独立，避免与 DUT 共用错误值 |

共享对象只保存 `Int`、`BigInt` 等 Scala 值。在模块生成上下文中再写成
`CSRAddresses.MCONFIGPTR.U(CSRAddresses.ADDRESS_WIDTH.W)` 等 Chisel 字面量，
避免跨模块缓存带硬件绑定关系的 `UInt`。术语、源码和规则入口见
[Chisel 编码规范](../../development/chisel-coding-standard.md)。

## 已实施的可读性改进

1. CSR 读表、写译码和权限逻辑使用统一地址名，去掉消费者中的裸十六进制地址。
   `ReadEntries` 每行保留一个寄存器；监督、Sstc 等可选组单独组合，便于检查配置边界。
2. 指令译码共用 `InstructionEncoding` 与 `InstructionDecode`，避免多个单元各自重复
   CSR/FENCE 的编码知识。语义位宽由常量表达，仍在使用处明确 Chisel 字面量宽度。
3. [Controller.scala](../../../src/main/scala/riscvpipeline/ieu/Controller.scala) 用具名
   `ControlWord` 字段生成原来的 13 位控制字，替代难以逐位阅读的二进制字符串；
   字段次序、取值和打包结果见 01。
4. 特权状态读写、异常委托、计数使能改用具名位号和掩码；硬件保留原有规范化、
   优先级及 WARL 行为。WARL 表示可写任意值、读回支持的合法值，具体规则逐表说明。
5. [PipelineBase.scala](../../../src/main/scala/riscvpipeline/PipelineBase.scala) 按 HPM 事件位名
   赋值 `Vec`，替代依赖拼接顺序识别事件的表达式；外部事件总线位序保持原样。
6. 计数器读表排序结果只构造一次；CPU 和生成入口复用配置默认值，减少同一默认值的重复定义。
7. 活动测试同步采用独立的测试常量；没有把测试预期直接替换为 DUT 的编码来源。

本次提取的是带架构、协议或配置含义的数字。布尔值、零初始化、普通循环计数和
清楚的局部算术仍可直接表达；常量定义文件中保留十六进制数是为了核对规范。
“参数常量”在这里包括固定的具名架构常量与可配置生成参数，两者在表中分别标识。

## 规范对照与实现边界

指令部分对照官方 [RV32I 2.1](https://docs.riscv.org/reference/isa/v20240411/unpriv/rv32.html)
及各页列出的 Zicsr、Zifencei；特权部分对照官方
[20260120 版 CSR 地址表](https://docs.riscv.org/reference/isa/v20260120/priv/priv-csrs.html)、
[Machine](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)、
[Supervisor](https://docs.riscv.org/reference/isa/v20260120/priv/supervisor.html) 和
[Sstc](https://docs.riscv.org/reference/isa/v20260120/priv/sstc.html)。
数字和位段以链接规范核对，实现行为以本仓库源码为准；规范后来新增的字段不代表本项目已实现。

本工程没有 MMU/PMP、浮点、原子或乘除法单元，也没有平台 CLINT/PLIC 本体。
整机地址转换固定 Bare；独立叶模块保存 `satp` 字段的能力不等于支持 Sv32。
部分独立子字模块有 64 位配置，整机仍只支持 RV32。
`misa` 的字母位、存在的 CSR 地址以及一次回归通过，都不能单独作为完整 ISA 符合性证明。
这些界限及当前实现选择在 01–04 中明确列出，本次不借常量重构扩展指令功能。

## 提交与验证记录

重构前已按要求提交快照：`7a35a1b1092daaf588b364dd806148f0a636bebd`
（`chore: snapshot before constants refactor`）。该快照保留开始重构前工作区的既有修改，
包括复位相关修改；本次常量重构以此为对照，不把这些先前修改归因于常量命名。

本报告与重构代码一并提交。2026-10-11 完成 314 项 Scala 测试、12 组 RTL 验收以及
默认特权顶层生成和 Verilator lint；具体命令、配置及验证边界见
[04 配置与验证](04-configuration-and-validation.md#本次验证记录)。
原模块逐轮设计与历史验收仍见 [第八章实施记录](../../chapter8/README.md)。
