# AHB/APB 桥精简验证报告

2026-10-21，按已确认计划精简 AHBAPBBridge 的数据寄存器。保留桥本身、
Idle/Setup/Access 三态、地址和写方向控制；删除 `WriteData:32`、`Strobe:4` 和
`HeldReadData:32`。三态仍表达地址接受、APB Setup 和 APB Access 等待的协议顺序，
没有因 CVW 使用另一种控制编码而删除这些阶段。

## 接口与参考依据

本地参考 `~/cvw/src/uncore/ahbapbbridge.sv:75–77` 将 PWDATA/PSTRB 直接连接
HWDATA/HWSTRB，并说明 AHB 数据相位信号保持至 READY。该文件90–99行默认
HRDATA 为零，再按 PSEL 选择 PRDATA；本项目只有一个 APB 外设，使用同样的单外设选择。

|信号或状态|精简后的职责|
|---|---|
|Idle、Setup、Access|保留状态推进、等待、背靠背事务及同步复位控制|
|地址、写方向|在 AHB 地址接受沿保存，APB 等待期间保持|
|PWDATA、PSTRB|分别直接连接 HWDATA、HWSTRB，不再经过桥内缓存|
|HRDATA|`PSEL ? PRDATA : 0`；Setup/Access 返回当前 PRDATA，Idle 返回零|
|上游 AHBInterface|地址接受沿保存 WriteBuffer/MaskBuffer；读完成沿保存 FetchBuffer|

AHB master 必须在 HREADY 等待期间保持 HWDATA/HWSTRB。真实 AHBInterface 已实现
该契约，桥内无需再次保存。读完成沿由 BusFSM 的 CaptureEn 驱动 FetchBuffer，
后续写不触发读捕获；桥进入 Idle 后可返回零。实现继续使用显式时钟和 Bool 同步复位，
没有增加请求或组合输出的 `&& !reset` 门控。

## 行为检查与证据

证据目录为 `target/ahb-apb-simplification/20261021/`。修改生产源码和新增测试前，
AHBAPBBridgeSpec、APBRegisterBankSpec、UncoreSpec 三个套件的基线17/17通过，
命令退出0。隔离快照的旧数字状态机也通过同样17项基线；两次基线只说明原实现
通过原有用例。

原桥的三个用例保留相位、等待、背靠背和同步复位检查，去除等待期间故意更改
HWDATA/HWSTRB 的非法 master 行为，并将 Idle 保持旧 HRDATA 的断言改为返回零。
另增一个当前 PRDATA/未选时零的读响应测试，以及三个等待配置的集成用例，共7项。
集成用例连接真实 AHBInterface、桥和 APBRegisterBank，覆盖 APB 额外等待0/1/4、
地址接受后改变上游请求输入、合法数据相位保持、部分字节写以及精确写完成次数。
固定数据为先全字写 `0x11223344`，再以掩码 `0x5` 写 `0xaabbccdd`，期望 scratch
为 `0x11bb33dd`、写计数为2；读完成后再写，检查 FetchBuffer 仍保持先前读值。

|检查|本轮结果|证据|
|---|---|---|
|修改前关联基线|17/17通过；3个套件，退出0|[baseline.log](../../target/ahb-apb-simplification/20261021/baseline.log)、`baseline.exit`|
|隔离快照的修改前数字状态机基线|17/17通过；3个套件，退出0|`snapshot-baseline.log/.exit`|
|RED：新契约用例对旧 ChiselEnum 桥|7项中2项通过、5项失败；5项均在 Idle 预期零而实际返回旧 HRDATA，命令退出1|`red.log/.exit`|
|GREEN：保留用户 ChiselEnum 的工作树，第九章关联回归|97/97通过；17个套件，失败/取消/忽略/待定均为0，退出0；含桥7项|`worktree-green.log/.exit`、`worktree-junit/`|
|最终提交索引快照的定向测试与 RTL 生成|21/21通过；桥、APBRegisterBank、Uncore各7项，3个套件；生成退出0|`snapshot-green.log/.exit`、[桥 RTL](../../target/ahb-apb-simplification/20261021/snapshot/target/bridge-snapshot-rtl/AHBAPBBridge.v)|
|最终提交索引快照的严格 lint|退出0，无剩余 RTL 警告；使用下文列出的两项忽略选项|`snapshot-lint.log/.exit`|
|最终提交索引快照的独立 RTL 整机矩阵|144/144通过，六种配置各24条，命令退出0；无缺失/重复，无证据核验错误|`snapshot-matrix.log/.exit`、`rtl-run-path.txt`、[运行报告](../../target/chapter9-testplan/runs/20261021T064053783979Z/report.md)|
|独立报告重验、SHA256SUMS|均退出0|`report-recheck.log/.exit`、`sha256-check.log/.exit`|
|运行源码与索引核验|176份源码的运行前后清单、归档、快照及主仓库暂存索引全部一致|[final-evidence-check.json](../../target/ahb-apb-simplification/20261021/final-evidence-check.json)|
|用户原有改动保留核验|七份非桥文件字节未变；工作树桥等于保存的原有 ChiselEnum 版本加精简，索引桥等于数字状态机精简版本|`preexisting-status.txt`、`preexisting.patch`、`preexisting-hashes.json`、`bridge-variants.json`、`final-evidence-check.json`|

RED 的失败符合新契约与旧桥的预期差异，未发生编译或工具错误；它不代表已独立
证明删除写缓存的效果。工作树 GREEN 覆盖全部第九章97项，较存储器 CE 调整时的93项
增加4项桥用例。生成的桥 RTL 仅保留 `State[1:0]`、`Address[31:0]`、`Write` 三个
功能寄存器；`RANDOMIZE_REG_INIT` 下的 `_RAND_*` 为仿真初始化辅助变量。
已核对 PWDATA/PSTRB 直接赋值及 PSEL 选择 PRDATA/零，未残留三个数据缓存。

最终矩阵为4个程序 × 2种存储配置 × RAM额外等待0/1/2 × 种子1/17/2026 ×
同实例复位2次，共144条。`summary.json` 的 fullMatrix、负向检查、源码稳定性及
代码覆盖门禁均为true，evidenceErrors为空，missingOrDuplicate为0。Python平台
检查22/22通过；六种配置均保留并核验实际line/toggle覆盖产物。

纯总线memory-switch的18条记录各完成128条提交的有界Spike比较，共2304条；
使用相同指令字节的`.text`地址适配，只映射ROM PC及经验证的JAL/JALR PC+4 link，
不跳过或重新同步。混合模式及MMIO/特权没有完整Spike差分，使用独立固定预期。
7份访存/观察窗口、8份IRQ和1份中间寄存器结果变体共16项真实轨迹负向检查均通过。
原书 CVW `ahb64_ram_{0/1/2}_{0/1}_rv64gc` 六种配置及 `fadd_b11-01` 未执行；
本项目的 RV32 整机矩阵不覆盖 RV64GC、浮点、缓存或 burst。
旧 [存储器 CE 报告](22-ram-ce.md) 的147/147和144/144，以及更早的
[EBU 精简报告](21-ebu-simplification.md)，均未替代本轮结果。

## 源码边界

本轮起点 HEAD 为 `e85f3aa06093cc13c992f21a2080938e0fd2a38c`。操作前已有8份用户
未提交文件，其中桥包含 ChiselEnum 状态编码重构；原始状态、补丁和哈希已保存。
本次工作树保留该重构，暂存索引仅包含相对 HEAD 数字状态机的数据缓存精简；用户的
状态编码改动继续留在未暂存工作树。两份桥的运行接口和三阶段协议契约相同，但源码
不同，分别取证。

|验证对象|源码身份|适用证据|
|---|---|---|
|当前工作树|原有 ChiselEnum 加本次精简；桥 SHA256 为 `53c1802f29780516426b2979e34fce75166b8ba0bc8dc1cd1d259411fb922a8c`|工作树 GREEN 和第九章97项关联回归|
|最终提交索引快照|HEAD 数字状态机加本次精简；桥 SHA256 为 `900239e71027175e953e8577fe1a8303019338ed59d5349a5f41a7cdac04b9bc`|独立21项定向测试、RTL/lint及144条整机矩阵|

以上是各自桥文件的哈希，记录于 `bridge-variants.json`；它们不能替代整份运行源码
清单。测试文件 SHA256 为 `bd8c56048cb35a1c216e5e0f8b88d05a9395de55b4a522c84c0dc655fbffbcc5`。
七份非桥原有文件保留原始字节；桥自身因本次精简而改变，不能声称原有8份文件全部
字节不变。核验已确认当前桥等于“原有工作树加本次精简”，暂存索引桥等于数字状态机
精简版本；完整逐文件结果保存在 `final-evidence-check.json`。

隔离快照位于 [snapshot/](../../target/ahb-apb-simplification/20261021/snapshot/)，绝对路径
记录于 `snapshot-path.txt`。矩阵启动前已将快照全部 Scala 文件与暂存索引逐字节核对，
随后冻结运行源码；工作树的 ChiselEnum 未进入这次矩阵。最终运行报告为
[`20261021T064053783979Z`](../../target/chapter9-testplan/runs/20261021T064053783979Z/report.md)。
176份运行源码的前后清单、归档、快照活动文件及主仓库暂存索引全部一致，独立报告与
SHA256SUMS核验均退出0。运行期间仅继续补充文档，未改变已冻结的运行源码。

## 复现

从主仓库执行以下命令，测试和生成在隔离快照内运行，使用主仓库统一 sbt 锁。
快照使用自己的 `scripts/sbt-local.sh`；外部工具链和缓存目录显式指定。

```bash
bridge_repo=/home/unlastingstar/cvw-experiment/pipeline/riscv-pipeline
bridge_snapshot="$bridge_repo/target/ahb-apb-simplification/20261021/snapshot"
bridge_run="$bridge_repo/target/chapter9-testplan/runs/20261021T064053783979Z"
cd "$bridge_snapshot"
export CHISEL_TOOLCHAIN_DIR=/home/unlastingstar/cvw-experiment/.tools/chisel
export CHISEL_CACHE_DIR="$bridge_repo/target/cache/chisel"
export JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx3g'
flock "$bridge_repo/target/chapter9-sbt.lock" ./scripts/sbt-local.sh \
  'testOnly riscvpipeline.uncore.AHBAPBBridgeSpec riscvpipeline.uncore.APBRegisterBankSpec riscvpipeline.uncore.UncoreSpec' \
  'runMain riscvpipeline.generators.GenerateAHBAPBBridge target/bridge-snapshot-rtl'
verilator --lint-only -Wall -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL \
  --top-module AHBAPBBridge \
  target/bridge-snapshot-rtl/AHBAPBBridge.v
python3 scripts/report/report-chapter9.py "$bridge_run"
(cd "$bridge_run" && sha256sum --check --quiet SHA256SUMS)
```

重新执行矩阵时，为 `--output` 提供尚不存在的目录，并沿用快照与工具环境：

```bash
flock "$bridge_repo/target/chapter9-sbt.lock" env SBT=./scripts/sbt-local.sh \
  python3 scripts/run/test-chapter9-rtl.py \
  --output "$bridge_repo/target/chapter9-testplan/runs/ahb-apb-rerun"
```

保留用户 ChiselEnum 的主工作树97项关联回归单独执行：

```bash
cd "$bridge_repo"
flock target/chapter9-sbt.lock ./scripts/sbt-local.sh \
  'testOnly riscvpipeline.ebu.* riscvpipeline.uncore.* riscvpipeline.ifu.BusIFUSpec riscvpipeline.lsu.BusLSUSpec riscvpipeline.BusHazardControlSpec riscvpipeline.BusPipelineSpec riscvpipeline.RiscvPipelineSoCSpec'
```

本次没有进行综合面积、工艺映射、时序收敛或性能基准验证。
