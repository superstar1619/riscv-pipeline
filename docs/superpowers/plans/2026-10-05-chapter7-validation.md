# 第七章完整验收与 CoreMark 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 恢复流水线 Scala/Verilator 验收，完成教材 §7.4 RV32I 架构签名比对和 CoreMark 功能/周期验证。

**Architecture:** 保持 RV32I 生产核心和接口；测试以提交顺序的 M 级访存事件、独立参考签名及有限周期为通过条件。大型软件的初始数据通过启动代码写入真实 DTIM，不改变 RAM 初始化契约。使用本地固定版本 riscv-arch-test/CoreMark 和 Spike 生成独立参考；Verilator 随机化未初始化状态。

**Tech Stack:** 保持 Chisel 3.6.1/Scala 2.13.14/sbt 1.10.7；现有 RISC-V GNU 工具链、Verilator、Spike。

**Spec:** 用户“继续”及“本轮连同 CoreMark 一起完成”，教材 §7.4（本地 PDF），docs/chapter7-pipeline.md 中已建立的硬件契约。

## Global Constraints

- 在现有 feat/chapter7-pipeline 分支继续，保留完整基线和历史测试；不合并、不推送。
- 不增加 CSR、异常、乘除法、缓存、预测；编译全部软件为 RV32I，乘除法使用软件实现。
- 每个程序有明确周期上限和失败状态，不能仅凭程序结束声称通过。
- 上游算法/测试向量不改写；仅适配平台启动、链接、数据初始化和停止/签名协议。
- CoreMark 为固定迭代的仿真功能及周期结果；没有真实频率及至少 10 秒测量时，不声称官方 CoreMark 得分。

## Review Focus

- 气泡及冲刷不能被当作丢失的提交事件；完整事件数量及顺序须检查。
- 子字字节通道和未对齐请求需区分 decoded request 与实际写入。
- 镜像替换与初始化不能依赖工作区历史状态；随机种子覆盖未初始化 RF/RAM。
- 程序和数据容量、入口高地址映射、签名区及栈不应别名覆盖。
- Spike 与 DUT 必须执行同一 ELF、同一初始化，参考生成超时或失败必须传播。

### Task 1: 流水线验收入口

**Files:** 新建 src/test/scala/riscvpipeline/CpuAcceptanceSpec.scala、src/test/cpp/PipelineRtlTest.cpp；修改 Makefile、scripts/run/test-rtl.sh。
**Interfaces:** 读取旧 .trace 的架构访存事件；top M 级接口保持不变。C++ runner 参数为事件文件、随机种子、周期上限。

- [x] 写验收测试：两种配置按顺序匹配每个 MemRW 非零事件，最终检查固定签名；原定向测试保留。
- [x] 确认旧 test-acceptance/test-rtl 的明确拒绝状态，然后恢复真实入口。
- [x] C++ 观察实际 M 写入、读请求和掩码；四种 CPU 配置各运行三个随机种子，末尾复位并重跑。
- [x] 验证：make test、make test-acceptance、make test-rtl；所有入口退出 0，签名一致。
- [x] 提交验收适配。

### Task 2: 教材 §7.4 架构测试

**Files:** 新建 scripts/build/build-validation.py、scripts/run/test-validation.sh、programs/validation 平台头/启动/链接脚本和 src/test/cpp/SignatureRtlTest.cpp。
**Interfaces:** 大型测试共用 524288-word IROM、32768-word DTIM，入口 0x80000000；目标镜像 target/validation/program.memfile，签名地址和值及运行元数据位于 target/validation。

- [x] 使用本地固定 riscv-arch-test rv32i_m/I 所有源文件及教材 misalign1-jalr 用例，保留原始向量与宏，关闭无关 trap/instret harness。
- [x] 所有初始 .data/.rodata 由启动代码写真实 RAM；.bss 清零，栈和 tohost 保留独立区域。
- [x] 同一 ELF 在 Spike 上生成参考签名，检查 timeout/退出状态和完整签名长度。
- [x] 使用共用 Verilator runner 收集所有 byte-mask 写入并比对完整签名；保留额外 poison/timeout 的负向检查。
- [x] 全部 I 测试通过；记录版本、测试/向量数量、覆盖范围及 FENCE 行为。
- [x] 提交架构测试平台和覆盖记录。

### Task 3: CoreMark

**Files:** 新建 programs/coremark/core_portme.h、core_portme.c、benchmark.c；扩展 validation 构建和测试入口。
**Interfaces:** 上游 core_list_join/core_matrix/core_state/core_util/core_main 文件保持原样；自定义仿真驱动调用原 iterate，2000-byte 数据、10 iterations，性能及验证种子；签名导出 seed/list/matrix/state/final CRC 和错误数。

- [x] 编译为 RV32I/ilp32，使用 RV32I libgcc 软件乘除，检查所有可执行指令为支持范围。
- [x] 同一 ELF 在 Spike 生成完整 CRC 参考，同时核对上游固定 list/matrix/state CRC。
- [x] Verilator 对性能及验证种子各运行三个随机种子，比较完整 CRC 和错误计数，记录纯执行区周期及 cycles/iteration。
- [x] 运行完整 Scala 和恢复后的 RTL/架构/CoreMark 入口；记录准确结果，独立只读分支评审，必要修正后提交。
- [x] 保留分支并报告提交号、可重复命令、结果和官方计分限制。
