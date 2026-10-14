# 单周期历史基线

本目录完整导入原 `single-cycle/riscv-single-cycle` 的设计说明与手写核验记录。
来源快照提交：`83dee1688105ea6b645f08cc5202827f71292e4a`，导入日期：2026-10-14。
测试数字、周期、提交号和停止范围保留原语境，不表示本次整理重新运行了 CPU 测试。

## 先区分两个阶段

- 第二章简化实现：[项目总结](project-summary.md)、[整机 RTL 核验](12-rtl-validation.md)、[Code Example 2.16](13-code-example-2.16.md)。这些文档描述当时五端口 CPU。
- 第七章完整 RV32I：[进度记录](chapter7-progress.md)、[最终整机验收](modules/20-riscv-single-chapter7.md)。最终接口增加 MemRW、Funct3 和有效 ByteMask，并在复位期间屏蔽访存副作用。
- [原项目导航](source-readme.md)保留完整模块、源码、程序和 RTL 入口，链接已指向本项目内的参考快照或 target 证据。

## 模块阅读

| 功能组 | 核验文档 |
| --- | --- |
| 运算与立即数 | [Extend](modules/01-extend.md)、[Cmp](modules/02-cmp.md)、[Shifter](modules/12-shifter.md)、[ALU](modules/03-alu.md) |
| 寄存器与执行 | [RegFile](modules/04-regfile.md)、[Controller](modules/05-controller.md)、[Datapath](modules/06-datapath.md)、[IEU](modules/07-ieu.md) |
| 子字访存 | [SwByteMask](modules/13-swbytemask.md)、[SubwordWrite](modules/14-subwordwrite.md)、[SubwordRead](modules/15-subwordread.md)、[DTIM](modules/16-dtim.md) |
| 第七章最终连接 | [LSU](modules/17-lsu-chapter7.md)、[IROM](modules/18-irom-chapter7.md)、[IFU](modules/19-ifu-chapter7.md)、[RiscvSingle](modules/20-riscv-single-chapter7.md) |
| 第二章早期连接 | [IROM](modules/08-irom.md)、[IFU](modules/09-ifu.md)、[LSU](modules/10-lsu.md)、[RiscvSingle](modules/11-riscv-single.md) |

## 规范、源码和复现

公共规范使用[本项目 Chisel 规范](../../development/chisel-coding-standard.md)；[原规范](historical-coding-standard.md)作为历史版本保留。
源码、测试、构建脚本、程序输入和波形查看配置位于[独立参考快照](../../../references/single-cycle/README.md)。
此快照不加入当前流水线构建，`riscvsingle` 包名和历史接口保持原样。

[历史运行证据](../../../target/reports/single-cycle/2026-10-14/report.md)包含导入日志、生成 RTL 和波形。
`make clean` 会删除这些运行产物；长期文档和参考快照保留。
按快照说明在 target 中建立工作副本后执行原命令，复现不依赖原 `../single-cycle` 项目。
