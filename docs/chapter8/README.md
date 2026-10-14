# 第八章：特权模块设计与历史实施

模块说明保留当时的核验结果和提交记录；当前运行从[报告导航](../reports/README.md)进入。

参考《RISC-V System-on-Chip Design, Edition 1》第八章，书页 335–381
（PDF 页 370–416），以及本地 CVW 源码。采用增量路线：各阶段交付独立模块、测试和中文说明。
2026-10-08 用户明确授权连续推进剩余阶段，取消中间等待检查；全部完成后统一检查。
用户在第二轮补充要求：每次继续前先完成上一轮 Git 提交；各轮验证通过后单独提交。

第八章全部 15 阶段已实现。`PrivilegedPipeline` 支持 CSR、精确异常、中断、
U/S/M 模式转换、WFI 和数据端序；原 `RiscvPipeline` 默认保持第七章行为。
使用入口见 [流水线集成](14-pipeline.md)，实际测试结果见 [整机验收](15-acceptance.md)。
2026-10-11 的命名常量重构、CSR 地址及状态位与指令集的逐字段对应关系，见
[常量重构与字段对照报告](../reference/constants/README.md)。

## 路线与状态

| 轮次 | 模块/阶段 | 职责 | 状态 |
| --- | --- | --- | --- |
| 1 | `CSRAccess` | 六种 CSR 指令访问、读改写、基础权限判断 | 已认可并提交：`7f70103` |
| 2 | `PrivDecode` | 特权指令译码及权限检查 | 已认可并提交：`711afd1` |
| 3 | `CSRStatus` | `mstatus/mstatush/sstatus` | 已认可并提交：`ebd56b5` |
| 4 | `CSRInterrupt` | `mie/mip/sie/sip` 和中断输入 | 已认可并提交：`1970fc7` |
| 5 | `CSRMachine` | M 模式陷阱、委托、配置和标识寄存器 | 已认可并提交：`bcdc7d8` |
| 6 | `CSRSupervisor` | S 模式陷阱及配置寄存器 | 已认可并提交：`26f8215` |
| 7 | `CSRCounters` | 周期、退休指令、时间及性能计数器 | 已认可并提交：`e11b2f7`；复位门控修改：`6fbb536` |
| 8 | `PrivMode` | 特权级状态及陷阱返回转换 | 已认可并提交：`3fdb5f8` |
| 9 | `Trap` | 异常/中断仲裁、优先级及委托 | 已实现、验证并提交：`c47ad49` |
| 10 | `WFI` | 等待、唤醒和超时 | 已实现、验证并提交：`1aa0765` |
| 11 | `EndianSwap` | RV32 数据大小端转换 | 已实现、验证并提交：`2d94a4b` |
| 12 | `CSR` | CSR 子模块汇总、陷阱记录和向量计算 | 已实现、验证并提交：`c9d3dfd` |
| 13 | `Privileged` | 特权单元连接 | 已实现、验证并提交：`6083ec4` |
| 14 | 流水线集成 | 检查并修改 IFU、IEU、LSU、Hazard 的必要接口 | 已实现、验证并提交：`2aa1a68` |
| 15 | 整机验收 | 陷阱程序、模式转换、中断及第七章回归 | 已实现并验证 |

各模块接口及实际集成边界见对应中文说明。当前 RV32 核心适用范围内实施第八章；
MMU、PMP、浮点单元、CLINT/PLIC 本体留给后续章节，相关接口和配置限制逐轮记录。
本轮未重复大型 CoreMark 验收。

## 已交付模块检查入口

```bash
make test-csr-access SBT=./scripts/sbt-local.sh
make generate-csr-access SBT=./scripts/sbt-local.sh
verilator --lint-only --top-module CSRAccess target/generated/csr-access/CSRAccess.v
make test-priv-decode SBT=./scripts/sbt-local.sh
make generate-priv-decode SBT=./scripts/sbt-local.sh
verilator --lint-only --top-module PrivDecode target/generated/priv-decode/PrivDecode.v
make test-csr-status SBT=./scripts/sbt-local.sh
make generate-csr-status SBT=./scripts/sbt-local.sh
verilator --lint-only --top-module CSRStatus target/generated/csr-status/CSRStatus.v
make test-csr-interrupt SBT=./scripts/sbt-local.sh
make generate-csr-interrupt SBT=./scripts/sbt-local.sh
verilator --lint-only --top-module CSRInterrupt target/generated/csr-interrupt/CSRInterrupt.v
make test-csr-machine SBT=./scripts/sbt-local.sh
make generate-csr-machine SBT=./scripts/sbt-local.sh
verilator --lint-only --top-module CSRMachine target/generated/csr-machine/CSRMachine.v
make test-csr-supervisor SBT=./scripts/sbt-local.sh
make generate-csr-supervisor SBT=./scripts/sbt-local.sh
verilator --lint-only --top-module CSRSupervisor target/generated/csr-supervisor/CSRSupervisor.v
make test-csr-counters SBT=./scripts/sbt-local.sh
make generate-csr-counters SBT=./scripts/sbt-local.sh
verilator --lint-only --top-module CSRCounters target/generated/csr-counters/CSRCounters.v
make test-priv-mode SBT=./scripts/sbt-local.sh
make generate-priv-mode SBT=./scripts/sbt-local.sh
verilator --lint-only --top-module PrivMode target/generated/priv-mode/PrivMode.v
```

源码、接口语义、测试覆盖与实际结果见 [CSRAccess](01-csr-access.md)、
[PrivDecode](02-priv-decode.md)、[CSRStatus](03-csr-status.md)、
[CSRInterrupt](04-csr-interrupt.md)、[CSRMachine](05-csr-machine.md)、
[CSRSupervisor](06-csr-supervisor.md)、[CSRCounters](07-csr-counters.md)、
[PrivMode](08-priv-mode.md)。

全部阶段按[连续完成计划](plans/2026-10-08-completion.md)完成，
当前新增说明：[Trap](09-trap.md)、[WFI](10-wfi.md)、
[EndianSwap](11-endian-swap.md)、[CSR 汇总](12-csr.md)。

最终连接与验收：[Privileged](13-privileged.md)、[流水线](14-pipeline.md)、[整机验收](15-acceptance.md)。

## 整体复验入口

```sh
make test-chapter8 SBT=./scripts/sbt-local.sh
make generate-privileged-cpu SBT=./scripts/sbt-local.sh
./scripts/sbt-local.sh compile 'Test / compile' test
make test-rtl SBT=./scripts/sbt-local.sh
```

独立叶模块 `Trap/WFI/EndianSwap/CSR/Privileged` 同样提供
`make test-trap/test-wfi/test-endian-swap/test-csr/test-privileged` 及对应 `generate-*` 目标。
最终统一检查从 [整机验收](15-acceptance.md) 开始。

## §8.3 TestPlan与本轮证据

[16：TestPlan实施与详尽报告](../../target/reports/imported-docs/2026-10-14/docs/chapter8/16-testplan-report.md)对照印刷页360–366和表8.5–8.9，
提供共享Scala/独立RTL的逐例预期、实际、周期、陷阱签名、配置适用性与检查器负向验证。
新入口为`test-chapter8-plan`、`test-chapter8-rtl`、`report-chapter8`；
新运行位于[`target/chapter8-testplan/runs/`](../../target/chapter8-testplan/runs/)，从[详细测试报告入口](../reports/README.md#chapter8-testplan)查看。
历史验收只作背景，§8.3结论均引用本轮重新执行的证据。
