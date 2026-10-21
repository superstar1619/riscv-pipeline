# 存储器 CE 常开验证报告

2026-10-21，先按用户要求将 RAMAHB 和 DTIM 的物理 CE 固定为1，随后按“同步检查
其他的是否也是这种情况并修改”扩展检查。当前 `src/main/scala` 共四个
`SyncReadMem` 实现：RAMAHB、DTIM、IROM、ROMAHB，本次均统一为物理端口 CE 常开。
逻辑请求接受、响应有效性、写完成时机与字节掩码继续遵守原有接口。

本地 CVW `~/cvw/src/uncore/ram_ahb.sv` 和 `rom_ahb.sv` 均使用 `.ce(1'b1)`。
CVW `src/ifu/irom.sv` 仍有外部 `ce`；本项目 IROM 本次将物理 CE 与公开 `io.en`
的响应有效语义分开，不能将这一调整归为 CVW IROM 原有实现。

|当前实现|物理存储器访问|保留的逻辑控制|
|---|---|---|
|RAMAHB|单个 masked `readWrite`，`en=true.B`|`ReadEnable`、`ReadIssued`、`HeldData`及写完成/掩码|
|DTIM|独立同步读，`en=true.B`；条件字节写|`MemRead && !MemWrite`、`ReadIssued`及写使能/掩码|
|IROM|同步读，`en=true.B`|公开`io.en`、`ReadIssued`和`HeldData`|
|ROMAHB|同步读，`en=true.B`|`Accept && !HWRITE`、`ReadIssued`和`HeldData`|

`RegFile` 使用 `Reg(Vec)` 与无条件组合读，没有物理 CE 端口；WE3 与复位属于写控制，
保持原样。`src/main` 未发现其他生成 Verilog 源或 BlackBox 存储器替代实现。
HSEL、请求有效、WE 和字节掩码各有协议或写入职责，不因物理 CE 常开而删除。

## 实现与接口语义

RAMAHB 继续使用单个原生 `SyncReadMem` masked `readWrite` 端口，`en` 无条件
连接 `true.B`。原先包围存储器访问的复位条件移到写模式 `PortWrite`：复位时为
false，其余周期取原有 `WriteEnable`。因此复位期间 CE 仍为1，完成沿遇到复位的
写被取消，RAM 内容不清空；未新增请求或响应的组合 `&& !reset` 门控。

复位沿之前的组合输出存在一处预期差异：旧实现的 `ReadBytes` 在复位条件下被默认零
覆盖，若此时 `ReadIssued` 为真，HRDATA 会立即变零；现在存储器端口持续活动，HRDATA
可保留前一读值直到同步复位沿。该沿将 `ReadIssued` 和 `HeldData` 复位为零，符合
既有同步复位约定。本次不声称复位断言到时钟沿之间的组合输出逐位等价。

`ReadEnable` 继续表示逻辑读请求，`ReadIssued` 标记下一拍有效读结果，`HeldData`
保存最近一次有效响应。空闲 HADDR 变化可改变内部读结果，HRDATA 仍由这套有效性与
保持逻辑限定。写后同沿接受读的单端口冲突等待、额外等待计数均保持原有时序。

DTIM 的 `RAM.read` 同样固定 `en=true.B`。逻辑 `MemRead && !MemWrite`、
`ReadIssued` 和无有效读响应时输出零的选择逻辑保持不变。写操作继续由
`MemWrite && ByteMask.orR` 触发，并使用原有逐字节掩码；同时读写请求仍只返回写语义。

IROM 与 ROMAHB 的 `ROM.read` 同样固定 `en=true.B`。IROM 保留
`ReadIssued = RegNext(io.en)` 和 `HeldData`，禁用期间即使地址变化也保持最近一次
有效响应，首次有效读之前输出仍未指定。ROMAHB 保留逻辑
`ReadEnable = Accept && !HWRITE`、`ReadIssued`、`HeldData`、同步复位和总线等待
控制，写请求继续以 OKAY 完成并忽略。两种 ROM 的初始化与一拍读延迟均未改变。

CE 是端口使能，WE 是写操作条件，HWSTRB/ByteMask 选择写入字节，三者职责不同。
生成器的 `.fir` 为 CHIRRTL。本轮已核对五种配置：RAMAHB 的 `rdwr mport` 以及
DTIM、IROM、ROMAHB 的 `read mport` 均放在 `when UInt<1>("h1")` 常量条件下，
没有展开后的 `.en` 字段。RAMAHB Verilog 的拆分读使能为
`1'h1 & ~(PortWrite & anyHWSTRB)`，拆分写使能为
`1'h1 & (PortWrite & anyHWSTRB)`，并保留各字节写掩码；DTIM 的读使能流水寄存器
每拍取 `1'h1`，两种 ROM 同样每拍开启物理读。这里 `anyHWSTRB` 表示生成 RTL 中
的 HWSTRB 或归约结果。
RAMAHB 读侧 `r_en` 随写模式和有效字节掩码变化，仍符合总 CE 常开的含义。DTIM
保留独立读、写端口。`generated-enable-review.json` 保存五种配置的 CHIRRTL
上下文与生成文件哈希；本次不改变端口组织，也不声称已映射到特定工艺存储器宏。

## 验证记录

证据目录：`target/ram-ce-always-on/20261021/`。在修改生产代码前加入两项行为回归：
先写入已知值，再让另一笔写的完成沿与复位重合，读回确认旧值保留；初始化两个字后，
在空闲期改变 HADDR、HWRITE、HWDATA 和 HWSTRB，检查 HRDATA 保持且另一字未被改写。
这些用例不依赖未初始化 RAM 内容，也不通过内部 CE 信号名称判断功能正确。

扩展至两个 ROM 后复用已有行为检查：ROMAHB 三项测试覆盖等待0/1/2、等待与空闲
期间地址变化、响应保持和写忽略；PipelineMemory 的五项测试包含 IROM 禁用时改变
地址仍保持输出、重新使能后同步读、取指 stall/redirect，以及 DTIM 字节写与子字访存。

|检查|本轮结果|证据|
|---|---|---|
|修改前 RAMAHBSpec、PipelineMemorySpec|13/13 通过，命令退出0|`baseline.log`、`baseline.exit`|
|仅调整 RAMAHB/DTIM 的关联 Scala|147/147 通过，23个套件，命令退出0；作为扩展前记录保留|`ram-only-affected.log`、`ram-only-affected.exit`|
|四种存储器调整后的关联 Scala|147/147通过，23个套件；错误、失败、跳过均为0，命令退出0|`affected.log`、`affected.exit`、`junit-all-memories/`、`junit-summary.json`|
|五种独立 RTL 生成|全部成功；ROM另生成完整镜像配置|`affected.log`、`generate-initialized-roms.log`及对应`.exit`|
|RAMAHB、DTIM32、DTIM64、ROMAHB、IROM 严格 lint|全部退出0，无警告；ROM使用完整镜像|`lint-{ram-ahb,dtim32,dtim64,rom-ahb,irom}.log`及对应`.exit`|
|未初始化 ROMAHB/IROM 的严格 lint|两次均因 UNDRIVEN 退出1，原始失败记录保留|`lint-rom-ahb-uninitialized.log/.exit`、`lint-irom-uninitialized.log/.exit`|
|五种 CHIRRTL/Verilog 的常量 CE 与写使能检查|已核对常量CE及保留的逻辑控制|`generated-enable-review.json`、`rtl/{ram-ahb,dtim32,dtim64,rom-ahb-image,irom-image}/`|
|独立 RTL 整机矩阵|144/144通过，六种配置各24条，命令退出0|`rtl-matrix.log`、`rtl-matrix.exit`、`rtl-run-path.txt`|
|独立报告重验、源码与 SHA256SUMS 复核|均退出0，无证据核验错误|`report-recheck.log`、`sha256-check.log`、`final-evidence-check.json`|

两个未初始化 ROM 配置均成功生成 RTL，严格 lint 因 ROM 没有镜像驱动报 UNDRIVEN。
未提供镜像本就是内容未定义的合法配置，无需为消除该警告修改生产逻辑。随后分别
使用覆盖全部1024字和64字的 RV32 NOP 镜像，生成到 `rtl/rom-ahb-image/` 与
`rtl/irom-image/` 后完成严格 lint；原未初始化产物保留于 `rtl/rom-ahb/` 和
`rtl/irom/`。`init/rom-ahb.hex` 与 `init/irom.hex` 仅为 lint fixture，不作为功能
程序通过的证据；功能仍由上述行为测试与整机矩阵验证。

关联 Scala 覆盖第九章全部93项（原91项加2项 RAMAHB 回归），并覆盖 LSU 子字模块、
PipelineMemory、PipelineHazard 和 PipelineSmoke。修改前13项中，RAMAHB 为8项，
PipelineMemory 为5项。仅调整 RAM 时三种独立 RTL 生成/lint 已通过，但不能替代
扩展后的五种 RTL 检查。首个矩阵 `20261021T024830177361Z` 因会话中断而停止；
第二个 `20261021T025354903726Z` 在范围扩展时发出中止，控制会话退出130，但其
后台收尾仍产生144条记录。由于运行期间源码发生变化，报告门禁拒绝这轮结果，最终
退出1；摘要见 `ram-only-run-final.json`。这些旧记录均不计为最终矩阵通过，原始
日志与路径记录继续保留。

整机矩阵沿用 [Test Plan](18-testplan.md)：4 个程序 × 2 种存储配置 × RAM额外等待
0/1/2 × 3 个种子 × 同实例复位2次，共144条。最终完整运行是
[`20261021T030135626760Z`](../../target/chapter9-testplan/runs/20261021T030135626760Z/report.md)。
报告器独立重新解析原始轨迹后退出0：144条全部通过，无缺失或重复，无证据核验错误；
IRQ 因果、真实轨迹负例、Spike 差分负例及六种配置的 line/toggle 门禁均通过。
所有运行文件的 SHA256SUMS 另经完整核验，退出0。

## 复现与证据边界

关联测试与生成命令使用统一 sbt 锁；下面的命令只重跑实际受影响的测试范围：

```bash
flock target/chapter9-sbt.lock env JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx3g' \
  ./scripts/sbt-local.sh \
  'testOnly riscvpipeline.ebu.* riscvpipeline.uncore.* riscvpipeline.ifu.BusIFUSpec riscvpipeline.lsu.* riscvpipeline.BusHazardControlSpec riscvpipeline.BusPipelineSpec riscvpipeline.RiscvPipelineSoCSpec riscvpipeline.PipelineMemorySpec riscvpipeline.PipelineHazardSpec riscvpipeline.PipelineSmokeSpec'
python3 - <<'PY'
from pathlib import Path
fixture_dir = Path('target/ram-ce-always-on/20261021/init')
fixture_dir.mkdir(parents=True, exist_ok=True)
for name, depth in [('rom-ahb.hex', 1024), ('irom.hex', 64)]:
    (fixture_dir / name).write_text('00000013\n' * depth, encoding='ascii')
PY
flock target/chapter9-sbt.lock env JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx3g' \
  ./scripts/sbt-local.sh \
  'runMain riscvpipeline.generators.GenerateRAMAHB target/ram-ce-always-on/20261021/rtl/ram-ahb 2' \
  'runMain riscvpipeline.generators.GenerateDTIM 32 64 target/ram-ce-always-on/20261021/rtl/dtim32' \
  'runMain riscvpipeline.generators.GenerateDTIM 64 64 target/ram-ce-always-on/20261021/rtl/dtim64' \
  'runMain riscvpipeline.generators.GenerateROMAHB target/ram-ce-always-on/20261021/rtl/rom-ahb-image 2 target/ram-ce-always-on/20261021/init/rom-ahb.hex' \
  'runMain riscvpipeline.generators.GenerateIROM 64 target/ram-ce-always-on/20261021/rtl/irom-image target/ram-ce-always-on/20261021/init/irom.hex'
SBT=./scripts/sbt-local.sh JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx3g' \
  python3 scripts/run/test-chapter9-rtl.py
```

验证使用当前工作区；操作前已有8份未提交文件不属于本次改动。原始清单、补丁和哈希
保存在 `preexisting-status.txt`、`preexisting.patch`、`preexisting-hashes.json`。
最终176份运行源码/平台文件与当前工作区哈希一致，原有8份未提交文件哈希保持不变，
见 `final-evidence-check.json`。2026-10-20 的完整 Scala694/694、Python105/105和 RTL144/144
属于 [EBU 精简验证](21-ebu-simplification.md)；本报告不将其记为存储器调整后的结果。

范围仍为当前 RV32 单次传输系统。DTIM64 独立展开用于检查已有位宽参数，不代表
RV64GC 整机验收；浮点、cache burst、AHB ERROR、综合面积、工艺映射与时序收敛未验证。
