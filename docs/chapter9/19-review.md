# 19 独立只读审阅与 Python 回归

依据 `interfaces.md`、本地 Chisel 编码规范、教材 §9.4–9.5（印刷页410–426），
并对照本地 `~/cvw/src/ebu`、`mmu/adrdec.sv`、`mmu/adrdecs.sv` 及 `uncore` 参考。
只读审阅范围：第九章 ebu/*、BusIFU、BusLSU、BusHazardControl、BusConfig、uncore/*，
以及 PipelineBase/BusPipeline 的总线连接、原配置默认路径、原始取消意图与提交条件。

检查了 IFU 虚拟地址接受及重放、数据相位响应归属、一次请求不重发、写载荷相位、
大小端字节通道、同步存储器延迟与保持、RAM 单端口结构等待、外设唯一副作用、
延迟 HSEL、None 同步复位及新模块没有组合 !reset 请求门控。
未发现已确认的 P0/P1/P2 正确性缺陷。ERROR、burst、cache 与完整第20章外设不在契约内，
未将这些缺项列为缺陷。

集成生成发现的实际 Redirect→IFU stall 组合反馈由集成方报告，后由 EBU 实现方修正。
本次复核 `BusIFU` 的导出 stall 仅依赖 Data 的 BusCommitted、寄存 BusPending、
当前映射/对齐及原始 BusFlushF；不再经过实际 RedirectM、PCSrcE、FlushD。
Mem3 不产生 IFU stall，已接受 Data 仍会等待完成；内部 BusDone 对实际取消的依赖
仅影响下一状态/消费寄存器，不再反馈全局 stall。
PipelineBase 取原始 TrapM、RetM、CSRWriteM/Fence 取消意图；实际重定向和 CSR/退休更新
仍在最终 stall 释放后执行。旧 bus=None 路径保留常量安全接线。

审阅时 RiscvPipelineSoC 的观察输出仍处于集成 TDD scaffold，故本报告不声称该顶层已完成审阅。
没有进行新的 sbt 行为测试。先前提出的 BusLSU 独立原始输入复位写入疑虑，不作为生产缺陷：
生产 Datapath 在 reset 时已经将 MemRWM 置为无请求。现有 SoC reset 重放测试也不能据此
声称覆盖“本地 store 正待完成时复位”的场景；该专门覆盖仍未核实。

## Python 回归证据

首次回归 96 项中94通过：一项失败是 Chapter8 checker 可执行文件缺失，另一项是
并行平台测试的 read_trace 红阶段窗口。该次日志保留在
`target/chapter9/regression/python-pre-fixture.log`，不作为最终通过证据。
Chapter8 默认路径缺少 current-run.txt，因此测试查找了不存在的默认二进制，而非 CPU 断言失败。

使用既有 `scripts/run/test-chapter8-rtl.py`，配置 `default`、用例 `CS01`、seed1，
在 `target/chapter9/regression/chapter8-checker-fixture` 新建 fixture。
既有脚本通过带统一 flock/JVM 参数的临时 SBT 包装器执行；未更改历史报告指针。
export、generate、lint、Verilator build、独立 CS01 两次复位执行及 checker 负例均成功；
fixture 的 results.json 共3条 pass。日志为 `target/chapter9/regression/chapter8-fixture-build.log`。
这是为检查器 Python 测试准备的最小 fixture，不是完整第八章 RTL 矩阵回归。

平台实现方完成 read_trace 后，设置 CHAPTER8_RUN_DIR 指向上述新 fixture，执行：

```bash
CHAPTER8_RUN_DIR="$PWD/target/chapter9/regression/chapter8-checker-fixture" \
PYTHONPYCACHEPREFIX=target/cache/python \
python3 -m unittest discover -s src/test/python -v
```

最终 **96/96 通过，退出0**，完整日志为 `target/chapter9/regression/python.log`。
本次未修改生产源文件、测试源文件、共享进度或报告脚本。
