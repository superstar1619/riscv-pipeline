# 10：WFI——有界等待、pending 唤醒与取消

`riscvpipeline.privileged.WFI` 管理 M 级 WFI 的等待时长。模块采用显式 `clk`、
高有效 Bool 同步 `reset` 的 `RawModule`，计数在上升沿更新，输出停顿和到期条件。
它不判断当前特权级、TW 或中断全局使能，相关资格分别由 PrivDecode 和 Trap 处理。

## 教材与 CVW 依据

- 教材 §8.2.2.4，书页 342（PDF 页 377）：WFI 的恢复与超时约束。
- §8.4.2，书页 370（PDF 页 405）：特权译码中的 WFI 计数及到期异常。
- 本地 CVW revision `2f82f001139358be7646e910ff6fa2a140bed620` 的
  `src/privileged/privdec.sv`：等待计数不受流水线 StallW 冻结，Trap 清计数；
  `src/privileged/privileged.sv`：wfiM、IntPendingM 与流水线等待的边界。
- [PrivDecode 说明](02-priv-decode.md)：本工程已经将 TW/模式到期资格和 WFI 识别独立出来。

本工程把 CVW 的计数从译码模块拆为独立叶模块，并将阈值从计数最高位扩展为
显式 `timeoutCycles`，因此支持非二次幂阈值。计数只累计有效 WFI 的等待沿；取消、
pending 和无 WFI 会清零，达到阈值后饱和。即使 M 模式或不受 TW 限制的 S 模式，
本实现也允许有界正常恢复；到期本身不等于非法指令异常。

## 参数与完整接口

| 参数 | 默认值 | 支持范围与单位 |
| --- | --- | --- |
| `timeoutCycles` | 65536 | 1..2²⁴，累计等待上升沿的阈值；非法值在 elaboration 时拒绝 |

计数位宽为 `log2Ceil(timeoutCycles + 1)`，保证可表示阈值自身：阈值 1/3/8/65536/2²⁴
分别产生 1/2/4/17/25 位计数，而不是仅能表示阈值减一。

| 方向 | 端口 | 位宽 | 含义 |
| --- | --- | --- | --- |
| 入 | `clk` | Clock | 同一流水线时钟域的上升沿 |
| 入 | `reset` | Bool | 高有效同步复位，下一上升沿将计数清零 |
| 入 | `io.ValidM` | Bool | 当前 M 级指令有效 |
| 入 | `io.wfiM` | Bool | PrivDecode 的完整 WFI 识别请求；到期仍保持识别 |
| 入 | `io.FlushM` | Bool | 外部取消当前 M 指令，立即屏蔽停顿和到期，沿上清计数 |
| 入 | `io.TrapM` | Bool | 当前指令已接受陷阱，立即屏蔽停顿，沿上清计数 |
| 入 | `io.IntPendingM` | Bool | 局部中断 enable 与 pending 相交；不依赖全局 MIE/SIE |
| 出 | `io.WFIStallM` | Bool | 当前 WFI 需要继续等待，供上层保持 M/E/D/F |
| 出 | `io.WFITimeoutM` | Bool | 有效且未被外部取消的 WFI 已达到阈值 |
| 出 | `io.WaitCycles` | UInt(计数位宽) | 当前寄存器计数，饱和于阈值；单位是等待上升沿 |

没有 StallM/StallW 输入：若把自身停顿接作计数使能，WFI 将永远到不了阈值。
输入 pending 必须由调用方提供；本叶模块既不保存 MIP/MIE，也不接受未经同步的外部请求。

## 时序、优先级与组合反馈边界

令 `Eligible = ValidM && wfiM && !FlushM`，`Active = Eligible && !TrapM`，
`Expired = WaitCycles >= timeoutCycles`，则：

```text
WFITimeoutM = Eligible && Expired
WFIStallM   = Active && !IntPendingM && !Expired
```

每个上升沿的状态更新优先级是：同步 reset 清零；非 Active 或 pending 清零；
未 Expired 时递增；否则保持阈值。复位、取消和 pending 的状态清零均发生在沿上，
所以 `WaitCycles` 不会因这些输入的组合变化提前跳到零。

`TrapM` 有意不参与到期输出的组合门控。到期先送入 PrivDecode 形成非法指令条件，
再由 Trap 接受；如果被接受的自身 Trap 又立即撤销到期，就会形成
`Timeout → Illegal → Trap → !Timeout` 组合反馈。这里 Trap 只立即取消 stall，
并在上升沿清计数；到期信号在该沿之前保持，沿后变零。回归测试专门检查此契约。
`FlushM` 表示外部取消，不能直接接入当前到期陷阱自身产生的流水冲刷。

pending 组合解除停顿，下一沿清计数；若 pending 与饱和计数同时出现，到期信号在清零沿
前仍由当前计数决定。未达到阈值时的 pending 唤醒不会产生到期。全局 IE 为零也能唤醒；
是否真正接受中断仍由 Trap 按特权级、全局 IE 和提交边界判断。

## 逐拍算例与上层责任

阈值设为 3，进入 M 时计数已经清零，Valid/wfi 持续为 1，无取消和 pending：

| 观察点 | WaitCycles | WFIStallM | WFITimeoutM |
| --- | --- | --- | --- |
| 第一个等待沿之前 | 0 | 1 | 0 |
| 第 1 个等待沿之后 | 1 | 1 | 0 |
| 第 2 个等待沿之后 | 2 | 1 | 0 |
| 第 3 个等待沿之后 | 3 | 0 | 1 |
| 后续仍保持该请求 | 3 | 0 | 1 |

在计数为 2 时 pending 变高，stall 当拍变零，下一沿计数回零。阈值 1 也保留
进入等待时的计数 0，只经过一个等待沿便到期；阈值 8 不在 7 提前到期。

上层需要在 stall 时保持 M/E/D/F，而较老 W 指令只能提交一次；WFI 唤醒或正常到期后
应先完成自身，在后继边界接受中断，使返回 EPC 指向 WFI 后继。该模块没有 wfiW、
退休、PC、特权级或时钟门控输出。背靠背 WFI 必须在两条指令之间提供清计数边界
（无 WFI、无效、取消或 pending 的上升沿）；没有指令身份输入，连续高 wfiM 被视为
同一条仍停留 M 级的 WFI。生产流水线如何建立边界由集成模块负责。

## 测试、生成与实际结果

```bash
./scripts/sbt-local.sh 'testOnly riscvpipeline.privileged.WFISpec'
./scripts/sbt-local.sh \
  'runMain riscvpipeline.GenerateWFI target/chapter8/completion/wfi/rtl-3 3'
verilator --lint-only --top-module WFI target/chapter8/completion/wfi/rtl-3/WFI.v
```

`GenerateWFI [targetDir] [timeoutCycles]` 默认输出到 `generated/wfi`，默认阈值 65536。
测试先运行计数恒零、stall/timeout 恒假的行为骨架，11 项中 10 项按缺失行为失败；
随后换为真实计数实现。11 项包含阈值 1/3/8 的逐沿 deadline、饱和、独立取消、pending、
同步复位优先、到期陷阱反馈边界、等待重新进入，以及固定种子随机序列。
随机测试每阈值 512 拍，共 1536 拍，用连续等待长度的独立软件模型检查沿前/沿后状态。
RTL 接口测试检查阈值 1/3/8/65536/2²⁴ 的 10 个端口、计数宽度和仅上升沿同步复位。

2026-10-08 的实际结果如下；日志目录为 `target/chapter8/completion/wfi/`。

| 检查 | 实际结果 |
| --- | --- |
| TDD 行为骨架 | 11 项中 10 项失败，缺失等待/计数行为；`red-behavior.log` |
| 实现与测试编译、独立仿真 | 11/11 通过，包括 1536 拍随机序列；`green.log` |
| RTL 生成 | 阈值 1/3/8/65536/2²⁴ 五组成功；`generate.log`、`rtl-*` |
| 接口及同步状态 | 五组均为 10 端口；计数 1/2/4/17/25 位，仅 `posedge clk` 状态更新 |
| Verilator lint | 五组退出码均为 0，无警告或错误；`lint-*.log`，Verilator 5.036 |
| 新增文件格式与链接 | Scala 95 列、无行尾空白，本页本地链接均存在 |

沿用既有 ChiselStage 弃用提示，不改 Scala/Chisel 工具链。此独立叶模块阶段没有运行
全套 Scala 测试或大型 CoreMark；统一回归由第十四、十五阶段负责。
叶模块仿真与 lint 不代表整机中断返回位置、功耗、CDC 或物理时序已被证明；流水线和
整机特权行为由后续联动验收覆盖。
