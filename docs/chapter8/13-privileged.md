# 13. Privileged：特权单元连接

教材第八章（书页 335–381 / PDF 370–416）；对照本地 CVW
`src/privileged/privileged.sv`、`privdec.sv`、`csr.sv`、`trap.sv`。
本模块将已验证的 CSR、PrivDecode、PrivMode、Trap、WFI 组合为统一特权单元。
流水线接口和整机验收见 [14](14-pipeline.md)、[15](15-acceptance.md)。

## 接口与事件

`Privileged` 是显式 `clk/reset` 的 `RawModule`。配置使用 `PrivilegedConfig`。
输入包括 M 级有效位、停顿、取消、指令、PC、源操作数、有效地址、
各类访存/译码异常、外部中断、64 位时间、退休信号和 HPM 事件。
输出包括 CSR 旧值、陷阱原因、当前/下一特权级、重定向地址、WFI 停顿、
有效访存端序及状态/计数器。详细信号名称与位宽以
[Privileged.scala](../../src/main/scala/riscvpipeline/privileged/Privileged.scala) 为准。

`ActiveM = ValidM && !FlushM && !reset`。
CSR 和模式状态在时钟沿提交，停顿禁止提交；同步复位具有寄存器更新最高优先级。
`TrapM` 表示当前有效指令的陷阱候选，寄存器提交还受 `StallW` 门控。
`CSRWriteM` 是访问许可通过后的写意图，实际软件写入还需有效、无停顿、
无取消、无陷阱。CSR 改写、合法返回及 FENCE/FENCE.I 使年轻指令重新取指。
重定向顺序为陷阱向量、返回 EPC、当前 PC+4。

`FlushM` 仅表示外部取消当前 M 指令。当前指令自身产生的重定向不能反过来
接到此输入，否则会取消自身并形成组合反馈。整机将该重定向送到年轻流水槽。

WFI 的计数达到阈值后产生 `WFITimeoutM`，由 PrivDecode 根据模式和 TW 判断
是否非法。Timeout 不以 Trap 组合门控，避免 Timeout→译码→Trap→Timeout 的环；
Trap 取消停顿并在下一沿清计数。
若外部仍保持 `StallW`，额外的同步保持位记住到期状态，直到提交、取消或指令离开；
不能因候选 Trap 清计数而丢失未提交的超时异常。合法 M/S WFI 可在阈值到达后恢复。
局部使能的待处理中断即使全局 IE=0 也可唤醒；唤醒和实际接受中断分开判断。
WFI 退休后，在后继指令边界接受中断并记录后继 PC。

## 示例

```asm
la    t0, machine_handler
csrw  mtvec, t0
li    t1, 0x40
csrrw t2, mscratch, t1   # t2 得到旧值，mscratch 更新为 0x40
# 设置 mepc/mstatus 后执行 mret 可进入 U/S 模式
machine_handler:
csrr  t0, mepc
addi  t0, t0, 4
csrw  mepc, t0
mret
```

委托异常进入 S 模式并更新 sepc/scause/stval；从 M 模式产生的异常不会降级委托。
中断向量模式仅对中断使用 `base + 4*cause`；同步异常使用 base。

## 验证

```sh
make test-privileged generate-privileged SBT=./scripts/sbt-local.sh
verilator --lint-only --top-module Privileged target/generated/privileged/Privileged.v
```

独立测试 **8/8 通过**：CSR 旧值与串行化、U→M→U、U→S→U、
M 中断与向量、WFI 局部唤醒与后继 EPC、S/TW 超时反馈、取消/停顿/复位、
陷阱优先及关闭 S 支持。默认能力、U/M 小配置、Sstc/HPM 三配置生成及 lint 通过。
此处测试连接后的特权单元；真正 CPU 指令执行结果见第 15 节。

仅支持 RV32 Bare；MMU/PMP/浮点/虚拟化及 CLINT/PLIC 本体未加入。
中断和时间输入必须已同步到本时钟域。Sstc 为可选能力，默认关闭。
