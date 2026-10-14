# 生成配置、接口对应关系与验证记录

导航：[报告入口](README.md) · [指令与控制](01-instructions-and-controls.md) ·
[CSR 地址](02-csr-register-map.md) · [特权字段](03-privileged-fields.md)。

## 配置与架构常量的区别

`CpuConfig` 和 `PrivilegedConfig` 在 Scala 生成阶段选择硬件结构和复位值，
不会生成同名的可编程 CSR。运行期间的软件通过 CSR 指令修改已实现的可写字段。
架构常量 `Architecture.XLEN=32`、CSR 地址及指令编码固定；配置改变支持范围，不能重新编号标准字段。
`Architecture` 全部 13 个常量及其单位、指令/寄存器对应关系见
[01 架构尺寸与指令字段](01-instructions-and-controls.md#1-架构尺寸与指令字段)。

## CpuConfig 全字段

来源：[CpuConfig.scala](../../src/main/scala/riscvpipeline/config/CpuConfig.scala)。
未特别说明时，下表默认值指直接调用 `CpuConfig()`；命令行生成入口的镜像默认值另见后文。

| 字段 | 类型、默认值 | 单位与约束 | 对应硬件／ISA 寄存器及字段 |
| --- | --- | --- | --- |
| `xlen` | `Int`，`Architecture.XLEN=32` | 位；当前只允许 32 | 整数寄存器与地址宽度；`misa.MXL[31:30]=01` 报告 RV32；不是运行时可切换位宽 |
| `imemDepth` | `Int`，`DEFAULT_IMEM_DEPTH=64` | 32 位指令字数；二次幂且 ≥2；容量 ≤2^32 字节 | IROM 容量，默认 256 字节；没有对应 CSR 字段 |
| `dmemDepth` | `Int`，`DEFAULT_DMEM_DEPTH=64` | 32 位数据字数；二次幂且 ≥2；容量 ≤2^32 字节 | DTIM 容量，默认 256 字节；没有对应 CSR 字段 |
| `resetVector` | `BigInt`，`DEFAULT_RESET_VECTOR=0` | 字节地址；`0 ≤ value < 2^32` 且四字节对齐 | 复位后 PC 起点；不是 `mepc`、`mtvec` 的复位值；这些 CSR 自有状态仍复位零 |
| `instructionInitFile` | `Option[String]`，`None` | `Some` 内容不得为空白；文件用于 ROM 初始化 | 生成/仿真镜像路径；无 ISA 寄存器对应 |
| `privilegedSupported` | `Boolean`，`false` | 生成阶段选择 | 接入特权单元、陷阱/中断和严格地址检查；无同名 CSR 开关 |
| `privileged` | `PrivilegedConfig`，`PrivilegedConfig()` | 下表字段的组合 | 特权模块参数包；在选择特权实现时使用 |

`RiscvPipeline` 默认使用 `privilegedSupported=false`；`PrivilegedPipeline` 的默认配置为 true，
其构造要求传入配置也为 true。两者共用流水线实现。
`DEFAULT_PROGRAM_FILE="programs/riscvtest.memfile"` 是生成命令的默认镜像路径，
不改变直接调用 `CpuConfig()` 时 `instructionInitFile=None` 的行为。

特权流水线中的指令窗口由 [IFU.scala](../../src/main/scala/riscvpipeline/ifu/IFU.scala) 计算：
`ROM_CAPACITY=imemDepth×4`、`ROM_BASE=floor(resetVector/ROM_CAPACITY)×ROM_CAPACITY`，
有效范围为 `[ROM_BASE, ROM_BASE+ROM_CAPACITY)`。
复位向量在窗口内，但不一定等于窗口起点；例如容量 512 字节、复位向量 `0x100` 时起点为零。
[LSU.scala](../../src/main/scala/riscvpipeline/lsu/LSU.scala) 的数据窗口从零开始，容量为 `dmemDepth×4`。
越界和未对齐请求按相应异常输入送入特权仲裁；普通第七章默认核心沿用原地址索引回绕行为。

## PrivilegedConfig 全字段

来源：[PrivilegedConfig.scala](../../src/main/scala/riscvpipeline/privileged/PrivilegedConfig.scala)。
字段如何作用于寄存器的细节见 [CSR 地址表](02-csr-register-map.md) 和
[特权字段表](03-privileged-fields.md)。

| 字段 | 类型、默认值 | 约束 | 对应架构字段及本项目行为 |
| --- | --- | --- | --- |
| `supervisorSupported` | `Boolean`，`true` | Sstc 开启时必须为 true | 控制 S 模式、S CSR、委托及相关状态/中断字段；`misa.S[18]` 随之报告；关闭 S 后仍保留 U/M |
| `bigEndianSupported` | `Boolean`，`true` | 可单独关闭 | 控制 `mstatus.UBE[6]`、`mstatush.SBE[4]`、`MBE[5]` 的端序能力，SBE 另受 S 支持限制；复位仍小端；端序作用于数据访问 |
| `sstcSupported` | `Boolean`，`false` | 要求 S 支持 | 决定 `stimecmp/stimecmph` 是否存在、`menvcfg.STCE[63]` 是否可写及 STIP 计时来源能力 |
| `vectoredInterruptsSupported` | `Boolean`，`true` | 可单独关闭 | 决定 `mtvec/stvec.MODE[1:0]` 能否保留 Vectored=1；关闭后规范化为 Direct=0 |
| `hpmCounters` | `Int`，`0` | 个数 `0..29` | 激活 `mhpmcounter3..(hpmCounters+2)` 及对应事件选择器、counteren/inhibit 位；未激活地址的行为见 02 |
| `hartId` | `BigInt`，`0` | `0 ≤ value < 2^32` | 固定的 `mhartid[31:0]` 读值；不是软件可写状态 |
| `wfiTimeoutCycles` | `Int`，`WFI.DEFAULT_TIMEOUT_CYCLES=65536` | 时钟周期数，`1..WFI.MAX_TIMEOUT_CYCLES`，最大 `1<<24=16777216` | WFI 模块有界等待阈值；与当前模式、`mstatus.TW[21]` 共同影响非法指令资格；无同名 CSR 字段 |

端序字段、陷阱向量和 TW 的标准含义参见
[官方 Machine ISA](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)，
STCE 与计时比较参见 [官方 Sstc](https://docs.riscv.org/reference/isa/v20260120/priv/sstc.html)。
WFI 阈值是本实现的周期策略，不是规范规定的统一常数，也不表示固定墙钟时间。

[WFI.scala](../../src/main/scala/riscvpipeline/privileged/WFI.scala) 中 `WaitCycles` 的宽度为
`log2Ceil(timeoutCycles+1)`。计数在有效等待时按时钟上升沿增加，达到阈值后饱和；
退出等待或出现局部使能后的 pending 会清零。自身产生的 stall 不冻结该计数。
`WFITimeoutM` 报告到期，最终是否触发非法指令由 `PrivDecode` 按模式和 TW 判断。

## 独立叶模块参数与整机支持范围

独立模块可以单独生成和测试，其构造选项不能视为 `PrivilegedConfig` 已开放的整机能力。

| 参数或能力 | 定义位置／默认值 | 对应字段 | 实际边界 |
| --- | --- | --- | --- |
| `virtualMemorySupported` | `CSRMachine`、`CSRSupervisor`、`CSRStatus`、`PrivDecode`；默认 false | 页故障委托、FIOM、satp、SUM 的字段保存及叶 SFENCE 译码 | 要求 S；整机 CSR 汇总未开启此选项，固定 Bare；TVM 受 S 支持控制，VM=false 仍可写并限制 S 访问 satp；叶模块没有 MMU/TLB/页表遍历 |
| `asidBits` | `CSRSupervisor`；默认 0 | RV32 `satp.ASID[30:22]` | 范围 `0..9`，非零要求 VM；只保留从 bit 22 开始的已实现低位 |
| 状态模块端序默认 | 独立 `CSRStatus` 的 `bigEndianSupported=false` | UBE/SBE/MBE | 不同于公共 `PrivilegedConfig` 的 true；整机显式传递公共配置，不应将二者混用 |
| 64 位子字处理 | `SubwordRead/SubwordWrite/SwByteMask` 的独立宽度参数 | LD/LWU/SD 等编码含义见 01 | 独立叶模块可处理更宽数据；`CpuConfig.xlen` 仍只接受 32 |

`satp` 的标准 MODE/ASID/PPN 布局参见
[官方 Supervisor ISA](https://docs.riscv.org/reference/isa/v20260120/priv/supervisor.html)。
标准的 Sv32 编码有明确含义；仅保存其字段不能完成地址翻译。

## 外部输入与内部信号的字段对应

`InterruptInputs` 是特权顶层输入包。所有中断电平须预先同步，`TIME_REGW` 须为同一时钟域内
一致的 64 位时间值；本项目没有 CDC、CLINT/PLIC 设备本体或软件可写的机器时间 MMIO。

| 输入字段 | 宽度 | 对应 ISA 寄存器／位 | 本项目的解释 |
| --- | --- | --- | --- |
| `MExtInt` | 1 | `mip.MEIP[11]` | 机器外部中断 pending 来源；使能另由 `mie.MEIE[11]` 控制 |
| `SExtInt` | 1 | `mip.SEIP[9]` | S 外部 pending 输入，与软件 SEIP 状态 OR；无 S 时屏蔽 |
| `MTimerInt` | 1 | `mip.MTIP[7]` | 外部机器计时中断 pending；不等于 time CSR 的某个固定 bit |
| `MSwInt` | 1 | `mip.MSIP[3]` | 外部机器软件中断 pending；不是本模块可写 CSR 状态 |
| `TIME_REGW` | 64 | `time[31:0]`、`timeh[31:0]`；Sstc 比较来源 | 低／高半观察同一时间输入；模块不增长、复位或写入它；原始 S 计时条件为 `TIME_REGW >= stimecmp` |

对应的标准 pending/enable 位布局参见
[官方 Machine 中断 CSR](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)。
SEIP 的软件状态与外部输入在读改写中的区别、STIP 的 STCE 选择见 03。

源码采用 F/D/E/M/W 表示取指、译码、执行、访存、写回阶段。信号后缀是流水阶段，
例如 `CSRAdrM` 的 M 不是“机器模式”缩写，`MSTATUS_REGW` 的 W 也不是 CSR 中的一个位。

| 代码字段／信号 | 对应架构字段 | 阅读时的区别 |
| --- | --- | --- |
| `CSRAdrM`（12 位） | CSR 指令 `instr[31:20]` | CSR 编号，不是访存物理地址 |
| `CSRWriteValM`（32 位） | 被访问 CSR 的新值 | 由 CSRRW/CSRRS/CSRRC 等操作形成；叶模块再按可写字段掩码处理 |
| `ReadVal`／`ModifyVal` 类信号 | 同一 CSR 的读结果／读改写基值 | mip/sip 返回的外部 pending 不能被一次读改写永久写回软件状态 |
| `NextCauseM`（6 位） | `xcause.Interrupt[31]` 与原因码低五位 | 内部 `[5]` 中断标志在保存到 CSR 时搬到 bit 31 |
| `PrivilegeModeW`（2 位） | 当前 U/S/M 模式 | 内部当前模式状态；`mstatus.MPP` 保存陷阱前模式，二者不是同一状态 |
| `STATUS_MPP`／`STATUS_SPP` | `mstatus.MPP[12:11]`／`SPP[8]` | 从 CSR 导出的字段，供陷阱返回和有效权限计算使用 |
| `HPMEventsM`（32 位） | `mhpmeventn` 的本地事件选择来源 | 事件 bit 从 0 编号，选择器从 1 编号；具体事件含义见 03；ISA 不统一规定这些事件编号 |
| `ValidM`、`StallW`、`FlushM`、`CSRWriteM`、`MTrapM/STrapM` | 无单独 ISA 字段 | 有效性、提交资格和仲裁控制；不能作为 CSR 地址或可编程状态理解 |
| `MemRW`、`ALUControl`、`Forward*` | 无单独 ISA 字段 | 内部协议，完整编码和使用位置见 01 |

## 生成命令参数

从仓库根目录调用；方括号表示可省略的位置参数。

```text
GenerateRiscvPipeline [imemDepth] [dmemDepth] [targetDir] [instructionInitFile|-] [resetVector]
GeneratePrivilegedPipeline [imem] [dmem] [dir] [file|-] [resetVector] [S] [BE] [Sstc] [HPM] [WFI]
```

两者默认指令/数据深度均 64，镜像为 `CpuConfig.DEFAULT_PROGRAM_FILE`，复位字节地址为零。
普通输出目录为 `target/generated/riscv-pipeline`，特权输出目录为 `target/generated/privileged-pipeline`。
镜像参数 `-` 表示 `None`；复位地址接受十进制或 `0x`/`0X` 十六进制。
特权入口的 S/BE/Sstc/HPM/WFI 默认值分别为 `true/true/false/0/65536`，复用配置默认值。
`hartId` 与 `vectoredInterruptsSupported` 没有对应的位置参数；需要通过 Scala 配置改变。

```bash
./scripts/sbt-local.sh \
  'runMain riscvpipeline.generators.GeneratePrivilegedPipeline 128 64 target/generated/privileged-custom programs/riscvtest.memfile 0 true true false 0 65536'
```

## 本次验证记录

验证日期为 2026-10-11，使用 JDK 17、sbt 1.10.7、Scala 2.13.14、Chisel 3.6.1、
chiseltest 0.6.2 与 Verilator 5.036。代码重构完成后执行以下验证；随后新增本报告，
未再修改硬件、测试及构建配置。文档收尾另检查源码摘要未变、链接、常量覆盖及空白。

| 检查 | 实际结果 | 本地日志 |
| --- | --- | --- |
| 重构前 Scala 基线 | 314 项通过 | `target/constants-refactor/baseline.log` |
| 重构后 `compile`、`Test / compile`、完整 `test` | 28 个 suite，314 项成功、0 失败、0 aborted；11:34:30 +0800 完成 | `target/constants-refactor/regression-final.log` |
| `make test-rtl` | 4 组配置 × 3 种子，共 12/12 PASS；每组复位重启检查通过 | `target/constants-refactor/rtl.log` |
| 默认 `PrivilegedPipeline` 生成及 Verilator lint | 生成成功、lint 成功 | 生成：`target/constants-refactor/rtl.log`；lint：`target/constants-refactor/privileged-lint.log` |
| 代码差异审查 | 常量值、位宽、13 位控制字、测试独立编码及原行为一致；`git diff --check` 无问题 | Git 差异及本报告 |

最终 Scala 日志摘要为 `Tests: succeeded 314, failed 0, canceled 0, ignored 0, pending 0`。
结果以 `regression-final.log` 为准。生成代码、工具缓存和日志均被 Git 忽略，表中路径用于当前工作区复查，
报告及复现命令随代码提交。已有 Chisel 弃用提示不影响本次编译和回归结果。

RTL 验收各使用种子 `1/17/2026`，配置如下（深度单位为 32 位字）：

| 用例 | IROM／DTIM | 复位地址 | 镜像 |
| --- | --- | --- | --- |
| book | 64／64 | `0` | `programs/riscvtest.memfile` |
| expanded | 128／128 | `0x100` | `programs/rv32-configtest.memfile` |
| acceptance | 128／64 | `0` | `target/acceptance-images/rv32-acceptance.memfile` |
| acceptance-expanded | 256／128 | `0x100` | `target/acceptance-images/rv32-acceptance-expanded.memfile` |

复现本次验证：

```bash
./scripts/sbt-local.sh compile 'Test / compile' test
make test-rtl SBT=./scripts/sbt-local.sh
make generate-privileged-cpu SBT=./scripts/sbt-local.sh
verilator --lint-only -Wall -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL \
  --top-module PrivilegedPipeline target/generated/privileged-pipeline/PrivilegedPipeline.v
git diff --check
```

本轮 lint 对象是默认特权整机；Scala 测试另覆盖相关可选配置。
本次没有重跑大型 CoreMark、完整上游架构测试，也没有进行形式等价或综合时序证明。
相应历史软件验收见 [第七章完整验收记录](../chapter7-validation.md)，
特权整机原始测试组织见 [第八章整机验收](../chapter8/15-acceptance.md)。
