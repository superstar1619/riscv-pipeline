# APB 完成条件精简验证报告

2026-10-21，按已确认计划删除 APBRegisterBank 的 Completed 位及其赋值，
将完成条件从 `Access && PREADY && !Completed` 改为 `Access && PREADY`。
Wait、PREADY、地址译码、字节掩码、写完成计数和同步复位保持原有行为，未增加
请求或组合输出的 `&& !reset` 门控。本轮不修改 AHB/APB 桥。

## 协议与参考依据

`Access = PSEL && PENABLE`。PREADY为低时，master保持请求字段并等待；
PREADY为高的Access在当前时钟沿完成，合法master在下一拍令PENABLE=0。
该拍可为PSEL=0的Idle，或PSEL继续为1的下一事务Setup，因此背靠背事务仍有独立
Setup阶段。外设仅在完成的写Access更新计数，并在scratch地址按PSTRB更新字节。
Setup和未完成的等待拍没有写副作用。

PREADY仅在 `PSEL && PENABLE` 的Access阶段用于判定完成，Idle或Setup时可以为0
或1。本轮保持 `PREADY := Wait === 0`：Idle/Setup将Wait装载为extraLatency，
额外等待大于0时，装载后的空闲PREADY为0符合协议；为0时不妨碍下一笔Setup开始。
桥在Idle独立输出HREADYOUT=1，不依赖外设PREADY，因此不会因空闲PREADY为0而
阻塞AHB。

原实现另用Completed锁住已完成的Access，在master继续保持PENABLE=1时抑制
后续写入；这超出本轮保留的合法master契约。删除该位后不再提供这种防重复保证，
不能继续用完成后持续Access的旧断言判断合法协议实现是否正确。

本地CVW `~/cvw/src/uncore/gpio_apb.sv:75–76` 和
`~/cvw/src/uncore/clint_apb.sv:56–57` 均以 `PWRITE & PENABLE & PSEL` 控制写入，
没有Completed位，PREADY固定为1。本项目外设支持额外等待0..255拍，因此仍需
PREADY限定完成；只精简Completed，不照搬常量PREADY。

scratch按完整PADDR(31,2)译码，保留高位防别名，PSTRB选择物理字节通道；
只读计数地址和未定义地址写不改变scratch。所有完成的写Access均加一次计数，
包括零掩码、只读及未定义地址写，PWDATA不能直接改写计数。同步复位仍清零
scratch、计数和Wait，等待逻辑的加载与递减条件不变。

## 测试与证据

本轮证据目录为 `target/apb-completion-simplification/20261021/`。
保留原APBRegisterBankSpec前六项功能用例，将最后的持续Access防重复测试替换为
等待0/1/2/255四种配置的合法协议检查。测试在等待期间保持字段，检查Setup与
PREADY为低时没有副作用，并在完成后下一拍使PENABLE=0；同时检查保持PSEL的
背靠背Setup、计数、字节掩码和同步复位。APBRegisterBank现为10项，直接关联范围为
Bank10项、Bridge7项和Uncore7项，共24项；第九章全章为100项。

|检查|本轮结果|证据|
|---|---|---|
|隔离快照的修改前基线|21/21通过；3个套件，退出0|[snapshot-baseline.log](../../target/apb-completion-simplification/20261021/snapshot-baseline.log)、`snapshot-baseline.exit`|
|新合法协议用例对修改前实现|24/24通过；3个套件，退出0|`pre-refactor.log/.exit`|
|主工作树第九章回归|100/100通过；17个套件，退出0，失败/取消/忽略/待定均为0|`worktree-green.log/.exit`、`worktree-junit/`|
|最终数字状态机索引快照的直接关联测试|24/24通过；3个套件，退出0，失败/取消/忽略/待定均为0|`snapshot-green.log/.exit`、`snapshot/target/apb-snapshot-junit/`|
|快照APBRegisterBank RTL生成及严格lint|显式等待2生成，生成/lint均退出0，无剩余RTL警告|`rtl-generate.log/.exit`、`lint.log/.exit`、[生成RTL](../../target/apb-completion-simplification/20261021/snapshot/target/apb-snapshot-rtl/APBRegisterBank.v)|
|生成RTL的结构核验|只保留Scratch:32、WriteCompletions:32、Wait:8；Completed消失，PREADY仍为Wait==0|`rtl-inspection.json`|
|快照独立RTL整机矩阵|144/144通过，命令退出0；无缺失/重复，无证据核验错误|`snapshot-matrix.log/.exit`、`rtl-run-path.txt`、[运行报告](../../target/chapter9-testplan/runs/20261021T123201630886Z/report.md)|
|独立报告及SHA256SUMS核验|均退出0|`report-recheck.log/.exit`、`sha256-check.log/.exit`|
|运行源码与暂存索引核验|176份运行源码的前后清单、归档、快照活动文件及主仓库索引逐份SHA一致；两个改动文件也匹配记录哈希|[final-evidence-check.json](../../target/apb-completion-simplification/20261021/final-evidence-check.json)|
|原有8份用户文件字节保留|8/8原始字节完全未变|`final-evidence-check.json`|

新合法协议用例在修改前后均通过，说明本次删除对已验证的合法master行为没有产生
功能差异；本轮没有功能RED。生成RTL另确认Completed位已删除，PREADY仍为
`Wait == 8'h0`，寄存器仍采用Bool同步复位；`_RAND_*`是仿真初始化辅助变量。
该RTL的SHA256为 `ddec66795f2296c277fcaa368a2a20feb186fdb5ad14d69aa2fa09e1605957f7`。
严格lint使用 `-Wall -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL`，无剩余警告。
JUnit独立汇总也确认前述24/100/24项及3/17/3个套件，见`junit-summary.json`；
独立代码复核未发现问题。

最终矩阵为4个程序×2种存储配置×RAM额外等待0/1/2×种子1/17/2026×同实例复位2次，
共144条，六种配置各24条。`summary.json`的完整矩阵、负向检查、源码稳定性和代码
覆盖门禁全部通过，evidenceErrors为空，missingOrDuplicate为0。Python平台22项通过，
六种配置的实际line/toggle覆盖产物均通过门禁。

纯总线memory-switch的18条记录各通过128条提交的有界Spike比较，共2304条。
该比较使用相同指令字节的`.text`地址适配，只映射ROM PC及经验证的JAL/JALR PC+4 link，
不跳过或重新同步；混合模式和MMIO/特权未做完整Spike差分，采用独立固定预期。
7份访存/观察窗口、8份IRQ和1份中间寄存器结果变体，共16项真实轨迹负向检查通过。

## 源码与历史证据边界

本轮起点HEAD为 `bb97ccf9e9d2591f4b01bc7564758cb4344a66b4`。
操作前有8份用户未提交文件，包含ChiselEnum重构。本次未编辑这些文件，包括桥；
核验确认全部8份文件的原始字节完全保留。本轮与桥精简时“七份文件不变、桥叠加精简”
的情形不同，已独立核验，未沿用报告23的保留结论。

|验证对象|源码身份|证据范围|
|---|---|---|
|主工作树|本次APB精简及用户原有ChiselEnum改动|第九章100项关联回归|
|隔离索引快照|HEAD数字状态机及本次APB精简|直接关联24项、RTL/lint和144条矩阵|

本轮两个改动文件的SHA256记录于 `runtime-hashes.json`：

|文件|SHA256|
|---|---|
|APBRegisterBank.scala|`44a6c7ba7276220bf82dde876e0c75b3a0ba3d8132b1c51d767e2f50a591de4b`|
|APBRegisterBankSpec.scala|`74594fa682a514c2a1648c9dd9c0038968296d3d8d2d93858c8328e50ddc8bd8`|

本轮隔离快照位于证据目录下的 `snapshot/`，不修改报告23的历史快照；160份Scala
文件已与主仓库暂存索引逐字节匹配。矩阵从冻结快照运行，运行目录为
[`20261021T123201630886Z`](../../target/chapter9-testplan/runs/20261021T123201630886Z/report.md)。
其176份运行源码的source-before与source-after完全一致，逐份SHA与归档、快照活动文件
和主仓库暂存索引一致。完整源码及原有8份文件的保留结果见
[最终证据核验](../../target/apb-completion-simplification/20261021/final-evidence-check.json)。

## 复现与范围

主工作树的100项与数字状态机快照的24项分开复现。生成和矩阵使用快照自己的sbt脚本，
所有sbt操作共享主仓库的锁。先显式设置外部工具链与主仓库缓存目录：

```bash
apb_repo=/home/unlastingstar/cvw-experiment/pipeline/riscv-pipeline
apb_snapshot="$apb_repo/target/apb-completion-simplification/20261021/snapshot"
apb_run="$apb_repo/target/chapter9-testplan/runs/20261021T123201630886Z"
export CHISEL_TOOLCHAIN_DIR=/home/unlastingstar/cvw-experiment/.tools/chisel
export CHISEL_CACHE_DIR="$apb_repo/target/cache/chisel"
export JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx3g'
cd "$apb_repo"
flock "$apb_repo/target/chapter9-sbt.lock" ./scripts/sbt-local.sh \
  'testOnly riscvpipeline.ebu.* riscvpipeline.uncore.* riscvpipeline.ifu.BusIFUSpec riscvpipeline.lsu.BusLSUSpec riscvpipeline.BusHazardControlSpec riscvpipeline.BusPipelineSpec riscvpipeline.RiscvPipelineSoCSpec'
cd "$apb_snapshot"
flock "$apb_repo/target/chapter9-sbt.lock" ./scripts/sbt-local.sh \
  'testOnly riscvpipeline.uncore.APBRegisterBankSpec riscvpipeline.uncore.AHBAPBBridgeSpec riscvpipeline.uncore.UncoreSpec' \
  'runMain riscvpipeline.generators.GenerateAPBRegisterBank target/apb-snapshot-rtl 2'
verilator --lint-only -Wall -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL \
  --top-module APBRegisterBank target/apb-snapshot-rtl/APBRegisterBank.v
python3 scripts/report/report-chapter9.py "$apb_run"
(cd "$apb_run" && sha256sum --check --quiet SHA256SUMS)
```

重新执行矩阵时，为`--output`提供尚不存在的目录，沿用快照和上述工具环境：

```bash
flock "$apb_repo/target/chapter9-sbt.lock" env SBT=./scripts/sbt-local.sh \
  python3 scripts/run/test-chapter9-rtl.py \
  --output "$apb_repo/target/chapter9-testplan/runs/apb-completion-rerun"
```

验收范围为当前RV32单次传输系统；教材原六组
`ahb64_ram_{0/1/2}_{0/1}_rv64gc`及`fadd_b11-01`未运行，RV64GC、浮点、cache和
burst均未覆盖。

[桥精简报告](23-ahb-apb-simplification.md) 的97/97、21/21和144/144，
以及 [存储器CE报告](22-ram-ce.md)、[EBU报告](21-ebu-simplification.md) 的结果，
均为先前运行，不代表删除Completed后通过。本次没有进行综合面积、工艺映射、
时序收敛或性能基准验证。
