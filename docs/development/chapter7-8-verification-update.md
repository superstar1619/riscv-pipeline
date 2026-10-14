# 第七、八章验证更新

本文记录统一验证入口及证据口径。后续开发流程见[验证指导](verification-guide.md)，尚未接入的模块与能力见[测试路线图](verification-roadmap.md)。本文中的“已接入”表示有可执行入口；某次回归是否通过，必须以该次 `summary.json` 和逐例记录为准。

## 教材与基线

教材为工作区 `../RISC-V System-on-Chip Design, Edition 1.pdf`。以下采用书内印刷页码，括号内为从 1 开始的 PDF 页码：

| 依据 | 页码 | 本工程对应 |
| --- | --- | --- |
| §5.11.3 Top-Level Testbench and Testvectors | 229（264）起 | 选择程序、构建、复位运行、完成协议、签名比较、批量汇总 |
| §7.2.1 同步存储器 | 319（354）起 | IROM/DTIM 延迟与访存对齐 |
| §7.2.2 Hazards | 321（356）起 | 前递、load-use、store→load、跳转冲刷 |
| §7.4 Test Plan | 329（364）起 | 39 个 RV32I 程序及 CoreMark |
| §8.3 Test Plan、表 8.5–8.9 | 360–366（395–401） | 特权指令、CSR、异常、中断 |
| §8.3.1 / §8.3.2 | 362 / 363（397 / 398） | 精确异常与可重复中断激励 |
| §8.4.5 Pipeline Hazards | 373（408）起 | CSR、陷阱、停顿和退休边界 |

本地 CVW 为 `/home/unlastingstar/cvw`，可通过 `CVW_ROOT` 指定。RV32I 来源为 `addins/riscv-arch-test`，固定 revision `c8c813af55ea79acb97fe832c5737cc4539af3ee`；CoreMark 为 `addins/coremark`，固定 revision `f3e8f2e0941e42961aadcc52750b1b5577c157c9`。第八章原生样例为 `tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/`。构建器保存上游清单、字节哈希及适配记录，不升级这些源码。

Chisel 3.6.1、Scala 2.13.14、sbt 1.10.7 和既有 Verilator/Spike 工具继续使用；每次运行的实际工具输出保存在 `metadata/`。生产 `RiscvPipeline` / `PrivilegedPipeline` 的端口契约保持原样，提交与流水线观察位于 Test 作用域包装层。

旧报告 [第七章验收](../chapter7/pipeline/chapter7-validation.md)、[第八章测试报告](../../target/reports/imported-docs/2026-10-14/docs/chapter8/16-testplan-report.md)、[CVW 联合报告](chapter7-8-cvw.md) 中的数量、周期与成功结论都是各自历史源码的结果，不能用作新读回协议和新差分观察层的验收证据。

## 当前可运行入口

从 `riscv-pipeline` 执行：

```bash
# 默认 all/smoke；推荐先执行
make test-verification SBT=./scripts/sbt-local.sh
# 选择套件或完整配置
make test-verification SBT=./scripts/sbt-local.sh VERIFY_SUITE=arch VERIFY_PROFILE=full
make test-verification SBT=./scripts/sbt-local.sh VERIFY_SUITE=privileged VERIFY_PROFILE=smoke
# RV32I 逐提交比较
make test-diff SBT=./scripts/sbt-local.sh VERIFY_PROFILE=full
# 必须重新执行 all/full，随后归档；失败结果也保留
make report-verification SBT=./scripts/sbt-local.sh
# 仅查看调度，不构建、不仿真
python3 scripts/run/verify.py --suite all --profile full --plan
```

`VERIFY_SUITE` 支持 `all|pipeline|arch|privileged|cvw|coremark`；`VERIFY_PROFILE` 支持 `smoke|full`。旧 `test-pipeline`、`test-arch`、`test-coremark`、`test-chapter8-rtl` 等仍可用。统一调度调用既有构建/运行适配器，不另外复制同类检查器；`all/full` 的全部 Scala 测试只执行一次，不重复调用 `test-acceptance`。

| 组合 | 执行范围 |
| --- | --- |
| all/smoke | `PipelineSmokeSpec`；addi-01、jalr-01、lw-align-01；default 配置 EX02/PR01/CS01/WFWAKE 的 RTL 与 Scala 动态采样；RV32I 差分与比较器故障注入 |
| all/full | 全部 Scala；四个生产 RTL 场景；39 个 RV32I；两种 CoreMark；既有四配置特权 RTL；CVW 样例；RV32I 差分；Python 检查器 |
| pipeline | smoke 为流水线冒烟；full 增加流水线/模块 Scala 与生产 RTL |
| arch | RV32I 签名与提交差分；smoke 限三个程序，full 为全清单 |
| privileged | 既有有序事件 RTL、Scala 整机动态采样及功能覆盖汇总；smoke 限上述四项，full 为全部四配置 |
| cvw | CVW 原生特权样例；smoke 使用 seed 1，full 使用 1/17/2026 |
| coremark | 性能模式、验证模式；正确性签名及测量区间周期 |

smoke 固定种子 1；full 种子 1/17/2026。既有套件保留原复位轮次；特权/CVW 和新增差分每个种子同实例复位两次。`all/smoke` 不等于全部套件都缩小执行：CoreMark 和原生 CVW 在 full 执行，也可显式选择单独运行。

## 流程和统一清单

`选择 → 编译 ELF/镜像 → 生成/复用配置仿真器 → 复位执行 → 完成与八拍观察 → 签名/提交比对 → 覆盖与证据汇总` 对应 §5.11.3 的程序级流程。

`manifest.json` 保留原始 ID，记录章节、配置、ELF/镜像、参考、检查方式、完成协议、周期上限、覆盖点以及需要的运行组合。底层 `arch.json`、`all.json`、`manifest.tsv` 和 CVW manifest 原样保留。软件生成的 ID 不会被统一调度重命名。

每次使用 `target/verification/runs/<UTC时间戳>/`，目录不可复用。相同硬件配置在本次套件中复用仿真可执行文件，ROM 文件使用相对路径，各程序在自己的不可变镜像目录中启动。命令记录保留实际工作目录，因此首个用例在末个用例之后仍可精确重放；负向检查使用独立 scratch 镜像。各次回归具有独立目录，避免覆盖正在使用的镜像。`latest-run.txt` 是定位指针，不是通过证据。

结果分开统计独立用例、用例/配置组合和运行记录；入口命令不加入独立用例数。去重键分别为 `(suite, 原始ID)` 和 `(suite, 原始ID, config)`；Scala 保留完整 JUnit 测试名，历史夹具已嵌在名称中的配置不会再猜测折叠。`pass`、`cpu_failure`、`timeout`、`build_platform_error`、`not_executed`、`not_applicable` 保留其语义。`not_applicable` 必须附能力边界；缺失必执行记录会补为 `not_executed`。原始退出码、命令、日志及补跑关联不被“通过”汇总覆盖。

## 签名与提交差分

第七章收尾代码通过 CPU 的实际 `LW` 读取 `begin_signature..end_signature`，再以普通 `SW` 发出索引和值。保留区：完成 `0x8021ff00`；CoreMark 时间标记 `0x8021ff10`；读回索引/数据/长度 `0x8021ff20/24/28`。长度单位为 32 位字。链接脚本显式保留协议区并检查栈和容量边界。

检查器同时核对 Spike 参考、实际 store 重建值及 CPU 读回值。store 重建仅提供独立对照与已写字节记录，不代替 CPU 读回。缺失、重复、错序、错误长度、未初始化签名字节及超时均失败。完成标记后继续八拍；终止循环的普通指令允许继续提交，迟到 store 或协议事件失败。

`VerificationObserver` 用真实推进条件把 M 级 PC/指令/访存信息对齐到 W，阻止停顿重复记录和冲刷指令正常提交。提交日志记录复位轮次、顺序号、周期、PC、指令、有效寄存器写回、访存地址/数据。启动代码显式初始化 GPR、数据和 BSS，不假设 RAM 在复位时清零。

Spike 与 RTL 使用同一个 ELF、RV32I ISA 与内存布局。比较器只允许删除 ELF 入口前的启动 ROM 前缀，随后严格逐条比较，不搜索下一条相似指令。访存宽度从指令独立解码，store 比较写入字节，load 通过有效寄存器写回检查；x0 规范化为零。两侧比较至完成 store，缺失尾部或意外陷阱失败。首差报告保留上下文、符号信息与 RTL 周期。CoreMark 不纳入提交差分；第八章尚不宣称 Spike 特权差分。

## 用例、断言与覆盖口径

既有特权用例继续承担 EX、PR、CS、IR、WF、EN、CT 和 ST 类行为检查。新定向程序补充前递来源、依赖距离、store 数据/地址、控制转移与停顿交叉；现有用例能表达的行为优先扩展原用例。

| 功能组 | 激励/检查依据 | 实际命中要求 |
| --- | --- | --- |
| 前递与依赖 | §7.2.2；ALU、分支、JALR、store 地址/数据；间隔 0/1/2 | 执行级实际前递源 + 提交结果 |
| 前递优先级 | M/W 同 rd 与 x0 | M 优先；x0 不参与错误前递 |
| 停顿/冲刷 | load-use、store→load、跳转 | F/D 保持或正确重定向；气泡/冲刷取消 |
| 精确异常 | EX02、PR01 等 | 更老 W 完成；故障正常结果与年轻副作用取消 |
| CSR | CS01–CS08 等 | 六种操作、旧值、零源、rd=x0、WARL、权限 |
| IRQ/xRET/WFI | IR*、WF*、IRLOAD | pending、使能、EPC、唤醒、返回与重入；IRQ pending × load-use 和 MIE 切换 |
| 大小端 | EN*、ENWIDTH 与 CVW endianness | 真实 byte/half/word、全部合法偏移、符号扩展、邻字节与 LE→BE→LE 切换 |
| 计数器 | CTRET、CTCARRY、CTTIME | 异常不退休；停顿不重复；64 位进位与禁止 |
| 复位 | 原有 store 提交边界；新增 load-use、WFI、陷阱入口附近复位；差分双复位 | 不依赖 RAM 清零，无非法副作用；从复位向量恢复 |
| 比较器敏感性 | 寄存器/store/顺序/截断故障注入 | 首差类型与注入故障一致，即使最终签名不变 |

表格定义验证意图，不代表每个交叉组合已经命中。功能覆盖必须由提交或真实流水线事件采样，失败运行单独保存而不用于通过验收；具体稳定 bin ID 由 `scripts/lib/verification_coverage.py::required_bins()` 定义，必达集合和实际首个命中证据见 `diff/functional-coverage.json`。其 `dependency.<consumer>.<A|B>.gap<0|1|2>`、前递、M 优先级、x0、分支结果、访存宽度/偏移和两类 stall 均从真实事件统计。第八章由 `Chapter8TestPlanSpec` 在真实 M 级退休/陷阱边界及 load-use/WFI 事件处采样，`scripts/lib/privileged_coverage.py` 汇总到 `privileged/functional-coverage.json`。各配置独立检查 CSR 六种操作/抑制、异常原因/来源/目标模式、中断 pending/全局使能/接受状态、WFI 唤醒及端序×宽度×偏移。每个 bin 保留首个实际周期、PC、指令、case/reset 文件和检查器映射；缺失轮次、无事件证据及失败运行不能满足必达集合。最小配置的 S 模式和大端按能力显式排除。计数器和运行中复位的定向断言仍按对应实际运行证据检查，不把测试名自动转成动态命中。

代码覆盖使用 Verilator 实测 line/toggle 数据；RV32I 在 `diff/code-coverage.json`、`diff/coverage.dat/info`，第八章在 `privileged/coverage/<config>/code-coverage.json` 和同目录 `.dat/.info`。统一入口启用 `CHAPTER8_COVERAGE=1`，每配置仅合并完整通过的运行，失败原始数据另留。保留未命中项，当前未应用排除项；没有采集的配置不能填成 0% 或 100%。人工功能矩阵不能替代代码覆盖。`--fcov` 尚未接通为本项目入口，不列为可用命令。

## 本轮结果与归档

本轮完整验收的权威入口是 `make report-verification`：任何未解决失败或必执行项未执行都会返回非零。Markdown 报告与原始证据都在 `target/verification/runs/<运行ID>/`，动态索引为 `target/reports/index.md`，不会覆盖历史目录。进入证据目录查看 `summary.json` 的 `passed`、`results.json` 中的逐例状态及 `report.md`；不能仅看末尾一个 PASS 字样。

每个目录应包含工具版本、运行前后源码哈希及快照、manifest、ELF、镜像、参考与实际签名、提交日志、差异记录、功能/代码覆盖、命令与原始退出码、逐例复现入口和 `SHA256SUMS`。尚未实际执行的结果不得写入本文成为固定成功数量。生成报告后应在评审中给出具体运行 ID；历史归档只作基线比较。

## 2026-10-14 最终完整验收实测

最终运行 ID 为 **`20261014T114922360658Z`**，`all/full` 返回 **0 / PASS**。当时的运行证据见[本轮总报告](../../target/reports/imported-docs/2026-10-14/docs/reports/verification/20261014T114922360658Z/report.md)、[汇总 JSON](../../target/reports/verification/20261014T114922360658Z/summary.json)和[逐例结果](../../target/reports/verification/20261014T114922360658Z/results.json)。运行前后源码哈希一致；归档 `SHA256SUMS` 的 9349 个文件已独立逐项核验通过。本节只使用这个最终目录中的产物。

早先 `target/verification-work/unified-smoke` 和 `unified-smoke-v2` 是实施中的冒烟试跑，均已通过；其后补充了退出码矛盾检查和差分漏执行回填。最终完整运行包含这些最终修正，不用试跑结果替代最终验收。

统一统计为 **820 个独立检查用例、1042 个用例/配置组合、2939 条运行记录**。这里按 `(suite, 原始ID)`、`(suite, 原始ID, config)` 去重，不把不同检查层的同一 ELF 合并成一个程序；Scala 使用原始 JUnit 名称。运行记录包括实际测试、检查器和覆盖验收记录。另有 11 条入口命令记录不加入上述 2939 条，因此逐行状态为 **2950 pass、11 not_applicable**，失败、超时、缺失和未执行均为零。11 项不适用全部来自 CVW 能力边界，原因保存在逐例结果中。

| 检查层 | 本轮实测 |
| --- | --- |
| 全量 Scala | 31 suites，603/603 测试通过；单次执行，未重复运行验收别名 |
| 原生产 RTL | 4 场景 × 3 seed × 2 次同实例复位，24/24 通过 |
| 第八章有序事件 RTL | 284 个配置/用例组合 × 3 seed × 2 次复位，1704/1704 通过 |
| RV32I 签名 | 39 程序 × 3 seed，117/117 通过；参考/store 重建/CPU 真实读回一致 |
| CoreMark | 2 模式 × 3 seed，6/6 通过 |
| CVW 特权程序 | 35 个适用配置/程序组合 × 3 seed × 2 次复位，210/210 通过 |
| RV32I 提交差分 | 39 架构程序 + dependencies 定向程序，3 seed × 2 次复位，240/240 通过 |
| 差分真实日志故障注入 | 13/13 按期望原因拒绝，包含覆盖后中间错误、store 地址/数据、顺序、截断和完成缺失 |
| Python 回归 | 67/67 检查通过；另保留各套件实际二进制的负向检查结果 |

功能必达点以真实事件为依据，全部满足；失败运行未参与合并：

| 配置 | 必达 bins | 未命中 | 证据 |
| --- | ---: | ---: | --- |
| RV32I 观察层 | 46 | 0 | [提交/流水线事件覆盖](../../target/reports/verification/20261014T114922360658Z/diff/functional-coverage.json) |
| privileged default | 87 | 0 | [第八章逐配置功能覆盖](../../target/reports/verification/20261014T114922360658Z/privileged/functional-coverage.json) |
| privileged minimal | 60 | 0 | 同上；S 模式、大端依能力排除 |
| privileged reset100 | 87 | 0 | 同上 |
| privileged sstc-hpm | 87 | 0 | 同上 |

第八章功能覆盖使用 568 个通过的 Scala 复位轮次，固定清单与导出清单一致。每个命中保留实际 PC、指令、周期、配置/用例/复位及原始证据路径，不能将这张必达表外推为所有可能时序交叉的穷尽证明。

Verilator line/toggle 实测如下，均仅合并通过运行，未应用人工排除项：

| 配置 | line 命中/点数 | 未命中 line | toggle 命中/点数 | 未命中 toggle | 原始摘要 |
| --- | ---: | ---: | ---: | ---: | --- |
| RV32I 观察层 + 生产一致性实例 | 1943/1995 | 52 | 12729/12771 | 42 | [摘要](../../target/reports/verification/20261014T114922360658Z/diff/code-coverage.json) |
| privileged default | 1928/1946 | 18 | 13420/13571 | 151 | [摘要](../../target/reports/verification/20261014T114922360658Z/privileged/coverage/default/code-coverage.json) |
| privileged minimal | 1660/1678 | 18 | 11570/11688 | 118 | [摘要](../../target/reports/verification/20261014T114922360658Z/privileged/coverage/minimal/code-coverage.json) |
| privileged reset100 | 1928/1946 | 18 | 13420/13571 | 151 | [摘要](../../target/reports/verification/20261014T114922360658Z/privileged/coverage/reset100/code-coverage.json) |
| privileged sstc-hpm | 2024/2063 | 39 | 14472/14672 | 200 | [摘要](../../target/reports/verification/20261014T114922360658Z/privileged/coverage/sstc-hpm/code-coverage.json) |

未命中点的具体源码位置/信号保留在各摘要旁的 `coverage.info` 和 `coverage.dat`，没有因功能表通过而删去。当前实测不是 100% 代码覆盖；后续需逐项分析未命中点并补充激励，或给出具体配置/不可达依据后才能建立排除项。CVW 独立大存储配置、无包装的四个小型生产场景未单独采集 line/toggle，不套用表中其他配置的数值。

CoreMark 的测量区间在三个 seed 下保持一致：performance 为 **10,533,211 cycles**，validation 为 **11,331,447 cycles**；包含初始化和真实签名读回的完成周期分别为 10,558,980 和 11,357,422。两者均通过原 CRC/错误数/迭代数检查。这是本轮正确性与周期证据，不是官方 CoreMark 得分认证。

## 目录与清理

文档导航见[文档索引](../README.md)。`docs/` 保存长期说明、手写历史记录和固定报告导航；生成报告、动态索引、原始结果、日志、运行源码快照和校验清单全部留在 `target/`。单周期冻结源码位于 `references/single-cycle/`。报告正文与证据放在同一次运行目录，入口只登记 target 报告索引。

`make clean SBT=./scripts/sbt-local.sh` 删除整个 `target/`，包括运行证据与本地缓存。生成报告与证据一起清理，长期文档、手写历史记录及单周期冻结源码保留。清理后需重新运行报告命令；新运行使用新的时间戳目录。
