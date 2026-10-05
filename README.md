# RV32I 五级流水线 CPU（Chisel）

依据教材第七章 §7.2–7.3，将原单周期核心改为 F/D/E/M/W 五级流水线。
Scala 包名为 `riscvpipeline`，顶层为 `RiscvPipeline`，接口为 `RiscvPipelineIO`，
生成入口为 `GenerateRiscvPipeline`。单周期基线保存在本工程独立 Git 仓库的 `main`，
本次实现位于 `feat/chapter7-pipeline`。

支持范围保持现有 RV32I；不包含乘除法、CSR、异常、中断、缓存或分支预测。
工具链保持 JDK 17、sbt 1.10.7、Scala 2.13.14、Chisel 3.6.1、chiseltest 0.6.2。
遵循 [Chisel 编码规范](docs/chisel-coding-standard.md)和
[本地 skill](.agents/skills/chisel-coding-standard/SKILL.md)。

## 时序与接口

| 级 | 主要职责 |
| --- | --- |
| F | 保存 PCF，默认取 PC+4，接受 E 级跳转重定向 |
| D | 接收 IROM 同步读结果及同沿采样的 PCD；译码、读寄存器和 W→D 旁路 |
| E | M→E/W→E 前递后运算、分支比较、JALR 基址及 store 数据；提供 DTIM 读地址 |
| M | 保存执行结果和访存信息；提供 DTIM 写地址/数据，处理同步读取的子字 |
| W | 保存加载或执行结果，在下一上升沿写寄存器文件 |

M 前递优先于 W，排除 x0、无效写入及 M 级加载结果。load-use 和共享 DTIM
地址端口的 store→load 冒险各停顿 F/D 一拍，并在 E 注入气泡。按实际源使用检测
依赖；E 级条件分支、JAL/JALR 重定向优先于停顿，并冲刷 D/E。链接使用该指令的
PC+4，JALR 清除目标最低位。

IROM 使用同步读，输出直接对应 D 级；停顿时保持最后有效读结果。DTIM 使用同步读、
字节掩码写和共享地址，写优先。LSU 在 E 级发出读请求，响应与 M 级地址偏移及
Funct3 对齐，再进入 W。RAM 无初始化或复位，未写位置未指定；地址按配置容量回绕。
非法或未对齐的子字加载返回零，存储不写入。

顶层保留显式 `clk/reset` 和原观察端口位宽：`WriteData/IEUAdr` 为 32 位、
`MemWrite` 为 1 位、`MemRW` 为 2 位、`Funct3` 为 3 位、`ByteMask` 为 4 位。
所有观察字段对应 M 级，`MemRW={read,write}` 报告该级请求，`MemWrite/ByteMask`
报告实际允许的写入。store 在该 M 级周期末的上升沿提交。
高有效同步复位清空流水有效位、恢复复位向量并抑制 RAM/寄存器写入；数据 RAM 和
非零寄存器保持。首次 store 的观察周期不再等于单周期执行周期。

## 验证入口

从工程目录执行：

```bash
./scripts/sbt-local.sh compile 'Test / compile'
make test-pipeline SBT=./scripts/sbt-local.sh
make test SBT=./scripts/sbt-local.sh
make test-acceptance SBT=./scripts/sbt-local.sh
make test-rtl SBT=./scripts/sbt-local.sh
make test-validation SBT=./scripts/sbt-local.sh
make generate-cpu SBT=./scripts/sbt-local.sh
make generate-cpu SBT=./scripts/sbt-local.sh \
  IMEM_DEPTH=128 DMEM_DEPTH=128 RESET_VECTOR=0x100 \
  INSTRUCTION_INIT_FILE=programs/rv32-configtest.memfile \
  CPU_TARGET_DIR=generated/riscv-pipeline128
```

三组定向测试为 `PipelineHazardSpec`、`PipelineMemorySpec`、`PipelineSmokeSpec`，
每个程序都有明确周期上限和通过条件；教材小程序在 80 拍内只依次产生
`地址96＝7`、`地址100＝25` 两次存储。测试探针仅通过测试包装层导出，生产接口不增加
调试端口。验证范围和结果记录见 [第七章实施记录](docs/chapter7-pipeline.md)。

`scripts/sbt-local.sh` 默认复用 `../../.tools/chisel`，缓存位于本工程 `.cache/`；
可通过 `CHISEL_TOOLCHAIN_DIR` 指定工具链。保留独立模块 `generate-*` 入口及 CPU 的
`IMEM_DEPTH`、`DMEM_DEPTH`、`INSTRUCTION_INIT_FILE`、`RESET_VECTOR`、
`CPU_TARGET_DIR` 参数。生成 RTL、构建缓存和波形由 `.gitignore` 排除。

## 完整验收与 CoreMark

继续阶段已通过完整 Scala 回归（122 项）、整机验收和四种 RTL 配置验收。
§7.4 覆盖上游 `rv32i_m/I` 全部 38 个程序及教材附加 JALR 用例；这 39 个程序
和两种 CoreMark 模式均各运行三个
随机种子，完整签名与 Spike 一致。可单独运行 `make test-arch`、
`make test-coremark`，或由 `test-validation` 一起构建、生成 RTL、检查签名。
详细覆盖表、CRC、周期和可重复配置见 [完整验收记录](docs/chapter7-validation.md)。

大型软件测试需要本地 CVW `addins/riscv-arch-test`、`addins/coremark`、
RV32I GNU 工具链、Spike 和 Verilator。`CVW_ROOT` 可指定 CVW 路径；源码 revision
固定并核对原始文件。生成参数使用 2 MiB IROM/128 KiB DTIM，启动程序通过实际
store 初始化数据，默认核心参数保持原值。构建产物和日志位于 `target/validation`。
CoreMark 使用 2000 字节、10 次迭代，检查上游 CRC 并报告仿真周期；
没有真实时钟频率及至少 10 秒测量，不报告官方得分。

历史 trace 保留；验收只比较其中有序的架构访存事件。旧单周期的模块/整机逐周期
测试归档在 `src/test/legacy`，活动流水线测试位于 `src/test/scala`。
`make build-acceptance` 仍可生成验收镜像和历史 trace，需要 RISC-V GNU binutils，
默认前缀 `riscv64-unknown-elf-`，可用 `RISCV_TOOL_PREFIX` 指定。
