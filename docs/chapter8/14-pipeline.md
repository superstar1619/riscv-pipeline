# 14. 流水线集成

第八章已接入真正的五级流水线。`PrivilegedPipeline` 为支持特权功能的顶层；
`RiscvPipeline` 默认配置继续提供原第七章 RV32I 行为及原端口。
二者通过 `PipelineBase` 共享 IFU、IEU、LSU 和 Hazard，Scala 继承不增加硬件层级。

## 顶层及配置

原六个观察输出和显式 `clk/reset` 保留。`PrivilegedPipeline` 另有五个输入：

| 输入 | 位宽 | 含义 |
| --- | --- | --- |
| `interrupts.MExtInt` | 1 | M 外部中断电平 |
| `interrupts.SExtInt` | 1 | S 外部中断电平 |
| `interrupts.MTimerInt` | 1 | M 定时器中断电平 |
| `interrupts.MSwInt` | 1 | M 软件中断电平 |
| `interrupts.TIME_REGW` | 64 | 时间计数器一致快照 |

全部信号要求来自同一时钟域或已同步；本项目不实现 CDC 或平台中断控制器。
电平中断需由平台撤销，处理程序使用 MRET/SRET 恢复执行。
`RiscvPipeline` 可由 Scala 显式设置 `privilegedSupported=true`，但其外部中断/时间绑零；
带平台输入的完整入口应使用 `PrivilegedPipeline`。

`CpuConfig` 增加 `privilegedSupported`（默认 false）和 `PrivilegedConfig`。
特权顶层要求前者为 true。默认配置支持 U/S/M、RV32 大端状态及向量中断，
Sstc 默认 false，HPM 默认 0，WFI 有界等待默认 65536 周期。
支持能力配置在生成时决定，不是软件运行时开关。

## 精确提交和数据流

```mermaid
flowchart LR
  F[IFU 取指及故障] --> D[译码和寄存器读]
  D --> E[运算及源值前递]
  E --> M[M 指令 PC 地址 源值]
  M --> P[Privileged CSR Trap Mode WFI]
  P --> W[CSR旧值或普通结果写回]
  P -->|取消年轻指令并重定向| F
  M --> L[LSU 故障及端序]
  L --> P
```

M 级保留本指令 PC、完整指令、前递后的 rs1 和故障标志。
CSR 结果在 M 级才可用，依赖者按 late result 停顿一拍，禁止 M 级转发尚未取得的
CSR 值，使用 W 级真实旧值。寄存器 CSR 源参与依赖判断；立即数形式不使用 rs1。
CSR 写入、陷阱、返回和合法 FENCE/FENCE.I 在 M 级处理，重取目标指令；M 重定向
优先于年轻 E 级分支。状态更新后重新译码，避免沿用旧权限/端序。

陷阱取消当前指令的 W 写回、当前存储和年轻 F/D/E 指令；较老 W 指令仍可完成。
当前陷阱不能通过 `FlushM` 取消自身。LSU 故障判断使用未被 Trap 屏蔽的原始访存
请求，真实写入另用 `SuppressM`，避免故障→Trap→访存屏蔽→故障的组合环。

WFI 保持 M 并停顿 F/D/E；较老 W 只完成一次，随后 W 插入气泡。
停顿中 E 操作数保存当拍前递值，避免较老 W 离开后丢失结果。
WFI 自身先退休，然后在后继指令边界接收已使能中断。
`minstret` 只计有效、未陷阱且未停顿的 M 指令；`mcycle` 在等待中仍计数。
HPM 事件位 0–4 为退休、M 加载、M 存储、load/CSR 依赖停顿、store→load 停顿，
其余位绑零；受 inhibit、权限和已实现计数器数量限制。

## 地址、异常和端序

特权配置中，指令 ROM 的字节容量 `C=4*imemDepth`；映射窗口
`[floor(resetVector/C)*C, floor(resetVector/C)*C+C)`。
数据 RAM 映射 `[0,4*dmemDepth)`。越界不执行容量回绕的别名指令或存储；
分别产生取指/加载/存储访问故障。不映射 MMIO，总线扩展留给后续工作。
原第七章默认配置仍按容量回绕，保证既有程序及验收时序。

IALIGN=32：仅实际采取的 branch/JAL/JALR 非四字节对齐目标产生异常，
EPC 为跳转源指令，tval 为目标（JALR 先清 bit0）。加载/存储对齐异常记录实际地址。
错误路径故障随流水有效位取消，RAM 无初始化/复位，未写数据仍未指定。

取指总是小端。数据大端由状态及 MPRV/MPP 决定；写数据先生成重复子字，再交换
完整 32 位字，字节掩码保持物理地址。读交换完整字后调整 byte/half 提取偏移。
因此 byte 不改变值，half/word 的内存字节排列正确，MPRV 可使用低特权级端序。

FENCE/FENCE.I 被串行化并重新取指；当前 ROM 不可自修改，没有缓存或 TLB。
SFENCE.VMA 在当前 Bare 配置中非法，satp 固定 Bare。
MMU/PMP、浮点、虚拟化、CLINT/PLIC 本体、原子扩展和乘除法不在本轮范围。

## 使用

```sh
make generate-privileged-cpu SBT=./scripts/sbt-local.sh
verilator --lint-only --top-module PrivilegedPipeline \
  generated/privileged-pipeline/PrivilegedPipeline.v
make test-chapter8 SBT=./scripts/sbt-local.sh
```

生成入口参数依次为 imem/dmem 字深度、目录、镜像或 `-`、复位字节地址、
S、大端、Sstc、HPM 个数、WFI 阈值。使用自有镜像时，其字位置需与上述 ROM 窗口匹配。
完整验收记录见 [15](15-acceptance.md)。
