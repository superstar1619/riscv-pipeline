# 教材阅读导引

主要资料为 [RISC-V System-on-Chip Design, Edition 1](../../../RISC-V%20System-on-Chip%20Design%2C%20Edition%201.pdf)。
使用书内印刷页码；PDF 页码比印刷页多 35。教材与本项目实现、验证的完整对应见[总索引](../README.md#教材实现与验证对照)。

建议顺序：

1. §7.1 单周期核心：结合[单周期最终接口](../chapter7/single-cycle/modules/20-riscv-single-chapter7.md)，理解组合读取和上升沿写入。
2. §7.2 流水线：结合[接口与时序](../chapter7/pipeline/interfaces.md)，理解同步存储器、前递、停顿与冲刷。
3. §8.1–8.2 特权原理：阅读[CSR 与特权指令详解](chapter8-csr-privileged-guide.md)，沿精确陷阱理解状态变化。
4. §8.4 实现：阅读[第八章模块导航](../chapter8/README.md)，对照 CSR、Trap、WFI 与流水线连接。
5. §5.2、§5.11.3、§7.4 和 §8.3：结合[验证指导](../development/verification-guide.md)，从功能和适用配置推导预期及观察点，再查看[运行报告](../reports/README.md)。

教材描述的完整 Wally、特权规范和本项目实现有不同的能力范围。
本项目尚未实现 MMU/PMP、缓存、乘除法、浮点及 CLINT/PLIC 本体；详解中的这些机制用于学习，不能据此认为硬件已经支持。
