# 第七章五级流水线实施记录

依据用户实施方案、教材 §7.2–7.3 和本地 CVW `src/ifu/irom.sv`、
`src/lsu/dtim.sv`、`src/ieu/controller.sv`、`src/ieu/datapath.sv`、
`src/hazard/hazard.sv`。只参考流水控制、前递和存储器连接，未移植扩展子系统。

## 完成内容

- 独立 Git 仓库保存完整单周期基线，功能分支 `feat/chapter7-pipeline`。
- 包名、顶层、IO、生成入口分别迁移为 `riscvpipeline`、`RiscvPipeline`、
  `RiscvPipelineIO`、`GenerateRiscvPipeline`，同步更新构建和脚本引用。
- F/D/E/M/W 各级数据、控制及有效位同步推进，采用显式上升沿时钟和 Bool 同步复位。
- M→E/W→E 前递、W→D 旁路，M 优先；x0、无效写入和 M 级 load 不参与前递。
- 独立 Hazard：load-use/store→load 停顿 F/D、E 注入气泡，E 重定向优先。
- 分支/JAL/JALR 在 E 判断；前递覆盖比较、JALR 基址及 store 数据，冲刷 D/E，
  链接使用对应 PC+4，JALR 清除目标最低位。
- IROM 同步读直接提供 D 级指令，与同沿采样的 PCD 对齐。停顿时保持最后读结果。
  保持寄存器只在禁用读期间选择，不在正常响应路径增加流水级。
- DTIM 共享地址：E 提供同步读地址，M 提供写地址、数据和字节掩码；写优先，读写
  请求互斥。同步响应与 M 级 Funct3/偏移对齐，子字处理后在下一沿进入 W。
- 复位清除有效位并立即抑制架构写入，PC 在复位沿恢复 resetVector；RAM 和非零
  寄存器保持既有契约。观察接口宽度不变，所有访存字段报告 M 级。

## 定向仿真

测试首次在真实单周期基线上执行，四项中三项按预期失败：首次 store 在第 1 拍
而非第 4 拍；没有 store→load/load-use 两拍停顿；DTIM 读地址变化立即改变输出。
教材最终两次 store 在基线上已通过，保留为架构结果检查。

最终三个测试类共 **18 项通过，0 项失败**：

| 测试类 | 项数 | 检查行为 |
| --- | --- | --- |
| PipelineHazardSpec | 7 | M/W 优先级、W→D 旁路、x0、有效写入、真实源使用、两类各一次停顿、F/D 保持、重定向优先 |
| PipelineMemorySpec | 5 | DTIM 同步采样/字节掩码/共享地址、IROM 同步读/停顿保持、非零向量取指对齐、连续有符号/无符号子字访问 |
| PipelineSmokeSpec | 6 | F/D/E/M 初始填充、教材最终 store、分支前递及错误路径冲刷、load→branch、JAL/JALR 链接和清位、运行中复位 |

程序仿真最多 80 拍，其余用例上限为 16–48 拍；纯组合 Hazard 检查为有限输入序列。
复位用例在 M 级存在待提交 store、W 级存在待写回值时施加复位，检查无残留写入，
非零寄存器保值，并在重新启动后通过真实 load 检查 RAM 保值。所有探针在测试包装
层使用 `chiseltest.experimental.expose`，生产端口不增加测试字段。

执行命令：

```bash
./scripts/sbt-local.sh compile 'Test / compile' \
  'testOnly riscvpipeline.PipelineHazardSpec riscvpipeline.PipelineMemorySpec riscvpipeline.PipelineSmokeSpec' \
  'runMain riscvpipeline.GenerateRiscvPipeline' \
  'runMain riscvpipeline.GenerateRiscvPipeline 128 128 generated/riscv-pipeline128 programs/rv32-configtest.memfile 0x100'
```

Scala 主/测试源码编译通过，两种配置 RTL 生成通过。Chisel 3.6.1 的旧生成器存在
已有弃用提示，按计划保留工具链版本，没有升级编译器或 Chisel API。

## RTL 核对

两种生成结果均核对：

- 顶层恰好 8 个端口：`clk/reset` 输入；6 个观察输出位宽为 32/32/1/2/3/4。
- 全部 6 个时序块只在 `posedge clk` 更新，没有负沿或异步复位事件。
- IROM 和四个 DTIM 字节通道的读地址均有同步采样寄存器；深度分别为 64 或 128。
- PCF 复位值分别为 `0x0`、`0x100`；D/E/M/W 有效位复位清零，包含独立 Hazard。

生成产物位于 `generated/riscv-pipeline` 和 `generated/riscv-pipeline128`，不提交缓存、
RTL 或波形。这些检查不代表综合质量或时序收敛验证。

## 实施决定与后续范围

按用户指定在 `riscv-pipeline` 原目录初始化仓库并建立功能分支，没有增加工作树。
未受时序影响的基础模块测试继续作为活动测试编译。受时序和接口改变影响的旧
单周期模块/整机逐周期测试归档在 `src/test/legacy`，当前时序检查由三组定向测试替代。
旧 trace、验收镜像和元数据保留供后续适配，旧 C++ 预期也归档保留。
`test-acceptance/test-rtl` 明确提示待适配，避免将单周期 trace 用作流水线验收。

本次不运行完整 Scala/Verilator 回归、CoreMark 或 §7.4 testplan；没有声称已完成完整
RV32I 验收。按用户要求保留功能分支和提交，验证通过后停止。
