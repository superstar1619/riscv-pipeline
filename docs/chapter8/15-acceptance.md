# 15. 第八章整机验收

本轮完成原路线全部 15 阶段；使用 `PrivilegedPipeline` 执行真实 RV32 指令，
并保留 `RiscvPipeline` 第七章回归。没有将独立 CSR 单测当成 CPU 验收。
本轮未重复大型 CoreMark，未声称完整 RISC-V 架构认证。

## 可复现命令

```sh
./scripts/sbt-local.sh compile 'Test / compile' test
make test-chapter8 SBT=./scripts/sbt-local.sh
make generate-privileged-cpu SBT=./scripts/sbt-local.sh
verilator --lint-only --top-module PrivilegedPipeline \
  generated/privileged-pipeline/PrivilegedPipeline.v
make test-rtl SBT=./scripts/sbt-local.sh
```

整机程序和独立预期在
[Chapter8AcceptanceSpec.scala](../../src/test/scala/riscvpipeline/Chapter8AcceptanceSpec.scala)。
测试使用手工指令编码与标签重定位，无需交叉汇编器；编码器不调用 DUT 译码逻辑。
运行时生成完整 ROM 镜像到 `target/chapter8-images/`。程序在 RAM 地址 512 写入
`0x5a` 结束；陷阱处理程序记录 EPC/cause/tval 到地址 1024 开始的三字记录；SSIP 重入程序额外记录 pending，为四字记录。
测试同时比对完整写入顺序、地址、原始写数据和字节掩码；因此结束签名之外，
故障和年轻存储意外提交、重复陷阱或缺失陷阱都会失败。

测试包装层使用 `expose` 观察状态，不增加生产顶层调试端口。
每次陷阱时检查当前/下一模式、原因、记录 EPC/tval；处理程序实际执行 CSR 和
MRET/SRET 恢复，不用测试直接修改硬件状态。

## 整机覆盖（17 项）

| 项目 | 关键断言 |
| --- | --- |
| 六种 CSR | 旧值、修改值、紧邻 rd 依赖、rs1 前递、零源与读抑制 |
| 精确陷阱 | 较老 W 完成，故障 rd 不写，年轻 store/JAL 取消 |
| 非法指令 | 未实现/只读 CSR、整数非法编码、EBREAK、非法 FENCE |
| 权限 | U/S 访问 M CSR 拒绝，真正 MRET 恢复 |
| ECALL/委托 | M/U/S 原因码、嵌套 MRET/SRET、M 异常不降级 |
| 目标未对齐 | taken branch/JAL/JALR 源 EPC、目标 tval、链接取消 |
| 错误路径 | 错误路径故障取消，未采用分支不因目标异常 |
| 数据异常 | load/store 未对齐、越界、RAM 回绕别名不可破坏 |
| 取指异常 | 非零复位地址、ROM 越界、别名指令不可执行 |
| M 中断 | 直接/向量模式，电平应答、单次处理及 MRET |
| S 中断 | 外部中断委托、S PC/SIE 和 SRET |
| SSIP 重入 | 软件 MIP 置位、保持 pending 跨 MRET/SRET 重入、第二轮 CSR 清除 |
| FENCE/FENCE.I | 四种合法/保留字段编码、重取 PC+4、完整退休顺序及存储副作用 |
| WFI 等待 | F/D/M 保持、较老 W 单次完成、年轻操作数前递保留 |
| WFI 中断 | 先退休 WFI，后继 EPC、返回和计数 |
| WFI 权限 | 合法 S 有界恢复、TW/S 和 U 超时精确非法陷阱 |
| 数据端序 | word/byte/half 读写、MPRV 低模式端序、取指不变 |

超时独立单元测试还覆盖：到期后外部保持提交停顿，再出现 pending 时，
已形成的非法异常必须保持到提交；不让计数清零使异常消失。
最大 WFI 阈值 `2^24` 验证参数和 RTL 位宽，未运行完整最大等待长度仿真。

## 实际结果

验收日期：2026-10-08。JDK 17、sbt 1.10.7、Scala 2.13.14、
Chisel 3.6.1、chiseltest 0.6.2、Verilator 5.036，沿用原工具链。

- 17/17 整机测试通过，结束签名及全部副作用与陷阱记录匹配。
- `compile` / `Test / compile` 通过；全量 **314/314，28 suites** 通过（含全部叶模块及原流水线/整机定向测试）。
- `Privileged` 三配置（S/大端、U/M 小端、Sstc/HPM）生成及 lint 通过，接口均 51 端口。
- `PrivilegedPipeline` 四配置（默认、U/M 小端、Sstc/HPM、S 非零复位地址）生成及 lint 通过，接口均 13 端口。
- 原 `RiscvPipeline` 四配置生成及 lint 通过，保持 8 端口；第七章四组 RTL 程序各 seed=1/17/2026，**12/12 PASS**，均通过复位重启检查。
- 新增/修改 Scala 文件 95 列、无 tab/尾空格，`git diff --check` 及文档本地链接检查通过。
- 独立审查提出的 WFI 超时保持及中断/FENCE 覆盖问题均已修复并复验，无待处理发现。

详细运行日志位于 `target/chapter8/completion/`，该目录为可重新生成的构建产物：
`final-suite.log`（314 项）、`legacy-rtl.log`（12 次 RTL）、
`final-lint.log` / `final-lint-offset.log`（11 份 RTL）、
`privileged/stall-timeout-red.log` / `stall-timeout-green.log`（超时问题先失败再通过）。
独立叶模块的配置和组合/同步属性记录见各模块说明。
既有 ChiselStage 弃用提示仍存在；本轮未升级工具链。

## 检查入口及范围

建议从 [14. 流水线集成](14-pipeline.md) 和本节程序预期开始，再检查
[13. Privileged](13-privileged.md)、[12. CSR](12-csr.md) 的连接与提交条件。
各叶模块说明保留各自独立接口契约；若旧章节说明“尚未接入 CPU”，
那是该轮交付时点，当前集成状态以第 14/15 节为准。

实现为 RV32 Bare U/M + 可选 S。页异常输入在顶层绑零，satp 固定 Bare，
SFENCE.VMA 非法；没有 MMU/PMP、浮点、虚拟化、CLINT/PLIC 本体或 MMIO 总线。
中断/时间为已同步的输入，当前 ROM 不可自修改。HPM 来源仅有第 14 节列出的五类。
这些范围限制不影响本轮所列实测能力，也不代表后续章节已完成。
