# EBU 精简验证报告

2026-10-20，按用户要求删除 `DataOwner`、`IFUPending`、`LSUPending` 及其更新逻辑，
两处 `ControllerInput.HREADYIn` 恢复为直接连接外部 `io.HREADY`。这三个寄存器的声明
共计 4 位；没有进行综合面积或时序测量。

## 实现与适用条件

当前 EBU 配套的 BusFSM 只发起单次 NONSEQ，每方同一时刻最多保留一笔请求。
双方都在 Adr 请求时，仲裁器优先发出 LSU 地址，同时保存 IFU 地址，允许 IFU 虚拟
进入 Data。随后 `IFUDisable` 在 LSU Data 期间屏蔽 IFU ready；LSU 完成时重放保存的
IFU 地址，再由 IFU 接收自己的响应。IFU Data 完成与新 LSU 地址接受可以在同一拍发生，
LSU 当时处于 Adr，不会捕获这一拍的 IFU 读响应。

`ControllerInput` 继续使用 `HREADYOut = HREADYIn && !Disable`；仲裁状态、
`IFUReqDelay`、Save/Restore 和请求选择均保留。读数据仍广播，实际捕获由每方
BusFSM 的 Data 状态与 ready 共同决定。地址保存、已接受写载荷保存，以及 Stall/Flush
期间已接受响应的捕获逻辑均未改动。没有新增组合 reset 门控。

删除前的额外 owner/pending 门控在这一配套时序中重复已有保护作用。本次结论限定于
当前单次传输实现；没有验证任意上游时序、多笔未完成请求、burst 或 AHB ERROR，
也没有进行 RTL 形式等价证明。

## 验证记录

证据目录：`target/chapter9-ebu-simplification/20261020/`。删除前先运行原有 EBUSpec，
删除后复用相同行为断言，不增加仅检查源码文本或寄存器名称的测试。

|检查|结果|证据|
|---|---|---|
|删除前 EBUSpec|9/9 通过|`baseline.log`|
|删除后第九章关联测试|91/91 通过，17 个套件|`chapter9.log`|
|GenerateEBU 与严格 Verilator lint|退出 0，无警告|`chapter9.log`、`lint.log`、`rtl/EBU.v`|
|全项目 Scala compile/test|694/694 通过，48 个套件，零跳过|`full-scala.log`、`junit/`|
|Python 全量检查|105/105 通过|`python.log`|
|整机 RTL Test Plan|144/144 通过，六种配置各 24 条|`rtl-matrix.log`、`rtl-run-path.txt`|
|独立报告重验与 SHA256SUMS 复核|均退出 0|`report-recheck.log`、`sha256-check.log`、`final-evidence-check.json`|

EBUSpec 检查等待 0/1/3/7 拍下 LSU 读/写与 IFU 同时请求、保存地址重放、读响应归属、
已接受写载荷稳定、Stall/Flush 后完成，以及完成后八拍无重复事务。另一用例检查
IFU Data 完成与较晚 LSU Adr 同拍接受，随后两个缓冲分别取得正确读数据。

完整 Scala 回归包含第八章 284 个程序各两次复位执行；568 份本轮结果已复制到
`chapter8-scala/`，时间戳和哈希见 `full-regression-summary.json`。首次全量命令的
JUnit 参数写法被 sbt 拒绝，日志保留为 `full-scala-attempt1.log`；修正运行时 reporter
设置后重新完整执行，最终退出 0。

整机矩阵遵循 [第九章 Test Plan](18-testplan.md)：4 个程序 × 2 种存储配置 × RAM
额外等待 0/1/2 × 3 个种子 × 同实例复位 2 次，共 144 条。每条检查实际 CPU LW
读回、有序架构写、物理字节写和完成后观察窗口，并重验中断因果与检查器负例。
该矩阵对 RV32 单次传输的范围和教材原始 RV64GC/浮点/cache burst 用例的缺项说明
继续沿用 [完整实验报告](20-lab-report.md#教材-test-plan-对照与缺项)。

本轮完整运行是
[`20261020T093133450541Z`](../../target/chapter9-testplan/runs/20261020T093133450541Z/report.md)。
独立重新解析原始轨迹后，144 条全部通过，没有缺失、重复或证据核验错误；IRQ 因果、
真实 trace 负例、Spike 差分负例与六种配置的 line/toggle 采集门禁均通过。全部运行文件的
`SHA256SUMS` 校验退出 0。176 份运行源码/平台文件与当前工作区哈希一致；操作前已有的
8 份未提交文件哈希保持不变。

## 复现与源码边界

所有 sbt 命令使用同一个 `target/chapter9-sbt.lock`，避免并行任务争用构建目录：

```bash
flock target/chapter9-sbt.lock env JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx3g' \
  ./scripts/sbt-local.sh 'testOnly riscvpipeline.ebu.EBUSpec'
flock target/chapter9-sbt.lock env JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx3g' \
  ./scripts/sbt-local.sh 'testOnly riscvpipeline.ebu.* riscvpipeline.uncore.* riscvpipeline.ifu.BusIFUSpec riscvpipeline.lsu.BusLSUSpec riscvpipeline.BusHazardControlSpec riscvpipeline.BusPipelineSpec riscvpipeline.RiscvPipelineSoCSpec'
env -u CHAPTER8_CASE_FILTER flock target/chapter9-sbt.lock \
  env JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx3g' ./scripts/sbt-local.sh \
  'set Test / testOptions += Tests.Argument("-u", "target/chapter9-ebu-simplification/20261020/junit")' \
  compile test
CHAPTER8_RUN_DIR="$PWD/target/chapter9/regression/chapter8-checker-fixture" \
  PYTHONPYCACHEPREFIX=target/cache/python python3 -m unittest discover -s src/test/python -v
SBT=./scripts/sbt-local.sh JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx3g' \
  python3 scripts/run/test-chapter9-rtl.py
python3 scripts/report/report-chapter9.py target/chapter9-testplan/runs/20261020T093133450541Z
```

验证使用当前工作区，包括操作前已有的 FSM 枚举及文档改动；这些既有改动不属于本次
提交。操作前的文件清单、补丁和哈希保存在证据目录的 `preexisting-status.txt`、
`preexisting.patch`、`preexisting-hashes.json`。整机运行另外归档所有相关 Scala 源码、
测试和平台文件，比较运行前后哈希，并记录命令退出码。

2026-10-17 的初版结果保留在原报告和原运行目录中，不替代本次精简后的验证。
