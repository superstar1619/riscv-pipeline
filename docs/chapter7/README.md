# 第七章：单周期到流水线

先读[单周期基线](single-cycle/README.md)，再读[流水线设计与时序](pipeline/README.md)。
教材 §7.1 建立 RV32I 数据流，§7.2 引入五级流水线、同步存储器和冒险控制。

| 阶段 | 阅读入口 | 关键区别 |
| --- | --- | --- |
| 第二章简化单周期历史 | [早期总结](single-cycle/project-summary.md)、[RTL 核验](single-cycle/12-rtl-validation.md) | 五个整机端口、简化指令子集和当时的复位行为 |
| 第七章完整单周期 | [最终验收与接口](single-cycle/modules/20-riscv-single-chapter7.md) | 八个端口、完整教材 RV32I、子字访存、复位期间屏蔽副作用 |
| 第七章五级流水线 | [流水线接口](pipeline/interfaces.md)、[实施记录](pipeline/chapter7-pipeline.md) | F/D/E/M/W、同步存储器、前递、停顿和冲刷 |

各阶段报告中的测试数量和周期只对应当时的源码与测量口径。
当前回归方法见[开发指导](../development/README.md)，实际运行见[报告导航](../reports/README.md)。
