# 文档导航

本项目按教材的学习顺序组织设计资料：单周期 → 五级流水线 → 特权操作。
长期说明和手写历史记录保存在 `docs/`；自动生成的报告、日志、RTL、波形和运行证据保存在 `target/`。

## 从哪里开始

- 运行与新增测试：[验证入口和能力边界](development/chapter7-8-verification-update.md)、[验证指导](development/verification-guide.md)。
- 理解 CPU 演进：[第七章导航](chapter7/README.md)，先读单周期最终接口，再读流水线时序。
- 理解 CSR、异常和中断：[教材阅读导引](textbook/README.md)、[第八章模块导航](chapter8/README.md)。
- 第九章总线实验：[模块导航与检查进度](chapter9/README.md)、[BusFSM 首模块报告](chapter9/01-bus-fsm.md)。
- 编写代码：[Chisel 编码规范](development/chisel-coding-standard.md)、[架构常量与字段对照](reference/constants/README.md)。
- 查看本次测试：[报告导航](reports/README.md)。历史通过结论只对应正文标明的版本和运行。

## 教材、实现与验证对照

页码为书内印刷页；本地 PDF 页码为印刷页加 35。

| 教材依据 | 本项目文档 | 对应实现 | 验证入口 |
| --- | --- | --- | --- |
| §2.7.1–2.7.3，56–70 页 | [单周期第二章历史](chapter7/single-cycle/project-summary.md) | 单周期早期简化实现；最终源码见独立快照 | [历史测试与复现说明](chapter7/single-cycle/README.md) |
| §4.3，181–190 页 | [编码规范](development/chisel-coding-standard.md) | Chisel 模块、时钟、复位、配置和常量 | 相关模块 Scala 测试与生成 RTL 核对 |
| §5.2、§5.11.3，202、229 页 | [验证指导](development/verification-guide.md) | 测试计划、程序平台、观察器和检查器 | `make test-verification`、`make report-verification` |
| §7.1，301–317 页 | [单周期基线](chapter7/single-cycle/README.md) | IFU、IEU、LSU；[源码快照](../references/single-cycle/README.md) | 快照复制到 target 后运行原项目测试 |
| §7.2–7.3，317–329 页 | [流水线设计与时序](chapter7/pipeline/README.md) | F/D/E/M/W、前递、停顿、冲刷、同步存储器 | `make test-pipeline`、`make test-rtl` |
| §7.4，329 页起 | [流水线历史验收](chapter7/pipeline/chapter7-validation.md)、[验证入口](development/chapter7-8-verification-update.md) | RV32I 架构程序、签名读回和提交差分 | `make test-verification VERIFY_SUITE=arch VERIFY_PROFILE=full` |
| §8.1–8.2，336–359 页 | [CSR 与特权详解](textbook/chapter8-csr-privileged-guide.md) | CSR、U/S/M、陷阱、xRET 和 WFI | 第八章对应模块测试 |
| §8.3，360 页起 | [第八章验收方法](chapter8/15-acceptance.md)、[报告入口](reports/README.md#chapter8-testplan) | 特权用例、配置矩阵、有序事件和负向检查 | `make report-chapter8`、`make report-chapter7-8-cvw` |
| §8.4，364–380 页 | [特权实现](chapter8/README.md)、[流水线集成](chapter8/14-pipeline.md) | CSR/Privileged、精确陷阱、端序及冒险门控 | `make test-chapter8`、统一回归 |
| §9.4–9.5，410–426 页 | [第九章总线实验](chapter9/20-lab-report.md) | AHB/EBU、Uncore、APB、IFU/LSU 与 SoC | `make test-chapter9`、`make test-chapter9-rtl`；[模块报告导航](chapter9/README.md) |

## 文档分区

| 目录 | 用途 |
| --- | --- |
| [textbook](textbook/README.md) | 教材阅读顺序、页码和特权机制详解 |
| [development](development/README.md) | 当前开发规范、验证方法、CVW 适配和后续路线 |
| [reference](reference/README.md) | 编码、CSR 地址、状态字段和配置对照 |
| [chapter7](chapter7/README.md) | 单周期基线与流水线设计、手写历史实施/验收记录 |
| [chapter8](chapter8/README.md) | 特权模块接口、集成、手写核验记录及历史计划 |
| [chapter9](chapter9/README.md) | 总线接口模块实验、中文报告及用户检查进度 |
| [reports](reports/README.md) | 固定导航，只链接 target 中的生成报告 |

## 历史记录和清理

手写模块文档包含当时的测试结果、提交号和适配决定，保留这些内容用于追溯。
第二章单周期五端口实现、第七章单周期八端口实现以及流水线实现分属不同阶段；
端口、存储时序和复位门控不能混用。当前实现能力以源码和开发指导为准。

`make clean SBT=./scripts/sbt-local.sh` 删除整个 `target/`，包括生成报告、导入的原始证据和动态索引。
本目录的说明、手写历史记录与 `references/single-cycle` 源码快照保留。
清理后运行相应报告命令生成新证据；新运行不会恢复旧运行的日期和结果。
