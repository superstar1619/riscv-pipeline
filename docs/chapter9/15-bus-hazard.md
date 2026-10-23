# 总线停顿与精确重定向

顶层唯一的 `Hazard` 同时处理前递、数据依赖，并将 IFU、LSU 和 WFI 等待合并为 M 级停顿。
原 `BusHazardControl` 的组合逻辑已并入 `Hazard.scala`。尚未接受的取指可由
M 级异常、返回、CSR 写或 fence 取消；尚未接受的数据请求只由当前 Trap 取消。
CommittedF/M 表示已进入不可撤回阶段，取消不能释放对应停顿。取消意图使用
特权单元的原始候选，不能使用受最终 StallM 限制的 RedirectM，否则构成组合回路。

本模块为纯组合电路，无 reset 输入；关联模块只使用同步寄存器复位。
它不冻结 W 级，原流水线继续将较老 W 级指令排空一次，防止重复写回。

当前组合关系：

```text
CancelF = RedirectIntentM && !CommittedF
CancelM = TrapM && !CommittedM
FlushBusF = CancelF
FlushBusM = CancelM
StallM = WFIStallM || (IFUStallF && !CancelF) || (LSUStallM && !CancelM)
StallF = StallD = ((LoadStallD || StoreStallD) && !PCSrcE) || StallM
FlushD = PCSrcE || RedirectM
FlushE = PCSrcE || LoadStallD || StoreStallD
```

`RedirectM` 仅参与流水线冲刷，不能参与请求取消或 `StallM` 计算。
无总线配置将 `IFUStallF/LSUStallM/CommittedF/CommittedM` 绑零，仍由同一个 Hazard
处理 WFI 和依赖。Datapath 的 `FlushM` 继续拥有 E/M 级清除优先权。
旧总线单元测试已并入 `PipelineHazardSpec`，额外覆盖依赖与总线等待、E 跳转、
Trap、CSR/fence/return 意图、实际 M 重定向及 WFI 的交叉组合。
生成入口为 `GenerateHazard [targetDir]`，默认 `target/generated/hazard`。

以下保留旧模块的历史验证记录。

2026-10-17 验证：先运行恒零占位实现，三个功能用例均失败；实现后
BusHazardControlSpec 3/3 通过，分别覆盖独立等待、已提交事务保护和各类取消。
GenerateBusHazardControl 成功生成 RTL，Verilator `--lint-only -Wall` 通过。
日志和生成文件保存在 `target/chapter9/modules/bus-hazard/`。
