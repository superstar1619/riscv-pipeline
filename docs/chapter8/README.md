# 第八章：特权操作分模块实施

参考《RISC-V System-on-Chip Design, Edition 1》第八章，书页 335–381
（PDF 页 370–416），以及本地 CVW 源码。采用增量路线：**每轮只交付一个独立模块，
测试和中文说明完成后停止，待用户检查并明确同意再继续。**
用户在第二轮补充要求：每次继续前先完成上一轮 Git 提交；各轮验证通过后单独提交。

当前已实现 [CSRAccess](01-csr-access.md)、[PrivDecode](02-priv-decode.md)、
[CSRStatus](03-csr-status.md)、[CSRInterrupt](04-csr-interrupt.md)、
[CSRMachine](05-csr-machine.md) 和 [CSRSupervisor](06-csr-supervisor.md)，独立于 CPU。
**独立模块已验证不等于 CPU 已支持 CSR**：现有 `RiscvPipeline` 仍为第七章 RV32I 核心，
CSR 写回、陷阱、中断和特权转换尚未接入。

## 路线与状态

| 轮次 | 模块/阶段 | 职责 | 状态 |
| --- | --- | --- | --- |
| 1 | `CSRAccess` | 六种 CSR 指令访问、读改写、基础权限判断 | 已认可并提交：`7f70103` |
| 2 | `PrivDecode` | 特权指令译码及权限检查 | 已认可并提交：`711afd1` |
| 3 | `CSRStatus` | `mstatus/mstatush/sstatus` | 已认可并提交：`ebd56b5` |
| 4 | `CSRInterrupt` | `mie/mip/sie/sip` 和中断输入 | 已认可并提交：`1970fc7` |
| 5 | `CSRMachine` | M 模式陷阱、委托、配置和标识寄存器 | 已认可并提交：`bcdc7d8` |
| 6 | `CSRSupervisor` | S 模式陷阱及配置寄存器 | 第六轮交付，等待用户检查 |
| 7 | `CSRCounters` | 周期、退休指令、时间及性能计数器 | 未开始 |
| 8 | `PrivMode` | 特权级状态及陷阱返回转换 | 未开始 |
| 9 | `Trap` | 异常/中断仲裁、优先级及委托 | 未开始 |
| 10 | `WFI` | 等待、唤醒和超时 | 未开始 |
| 11 | `EndianSwap` | RV32 数据大小端转换 | 未开始 |
| 12 | `CSR` | CSR 子模块汇总、陷阱记录和向量计算 | 未开始 |
| 13 | `Privileged` | 特权单元连接 | 未开始 |
| 14 | 流水线集成 | 检查并修改 IFU、IEU、LSU、Hazard 的必要接口 | 未开始 |
| 15 | 整机验收 | 陷阱程序、模式转换、中断及第七章回归 | 未开始 |

后续模块的接口以各轮检查后的设计为准。当前 RV32 核心适用范围内实施第八章；
MMU、PMP、浮点单元、CLINT/PLIC 本体留给后续章节，相关接口和配置限制逐轮记录。
首轮不重复大型 CoreMark 验收。

## 已交付模块检查入口

```bash
make test-csr-access SBT=./scripts/sbt-local.sh
make generate-csr-access SBT=./scripts/sbt-local.sh
verilator --lint-only --top-module CSRAccess generated/csr-access/CSRAccess.v
make test-priv-decode SBT=./scripts/sbt-local.sh
make generate-priv-decode SBT=./scripts/sbt-local.sh
verilator --lint-only --top-module PrivDecode generated/priv-decode/PrivDecode.v
make test-csr-status SBT=./scripts/sbt-local.sh
make generate-csr-status SBT=./scripts/sbt-local.sh
verilator --lint-only --top-module CSRStatus generated/csr-status/CSRStatus.v
make test-csr-interrupt SBT=./scripts/sbt-local.sh
make generate-csr-interrupt SBT=./scripts/sbt-local.sh
verilator --lint-only --top-module CSRInterrupt generated/csr-interrupt/CSRInterrupt.v
make test-csr-machine SBT=./scripts/sbt-local.sh
make generate-csr-machine SBT=./scripts/sbt-local.sh
verilator --lint-only --top-module CSRMachine generated/csr-machine/CSRMachine.v
make test-csr-supervisor SBT=./scripts/sbt-local.sh
make generate-csr-supervisor SBT=./scripts/sbt-local.sh
verilator --lint-only --top-module CSRSupervisor generated/csr-supervisor/CSRSupervisor.v
```

源码、接口语义、测试覆盖与实际结果见 [CSRAccess](01-csr-access.md)、
[PrivDecode](02-priv-decode.md)、[CSRStatus](03-csr-status.md)、
[CSRInterrupt](04-csr-interrupt.md)、[CSRMachine](05-csr-machine.md)、
[CSRSupervisor](06-csr-supervisor.md)。
