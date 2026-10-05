# RISC-V 流水线 CPU 开发基线（Chisel）

从 `single-cycle/riscv-single-cycle` 精简复制，供后续流水线 CPU 改造使用。
**当前硬件仍是已验证的单周期 RV32 实现**，Scala 包名 `riscvsingle`、顶层
`RiscvSingle` 和生产接口保持原样；本次仅迁移工程，尚未加入流水级、前递、停顿或冲刷。
原工程保留，可用于对照实现和历史验证记录。

## 保留内容

- `src/main/scala`：CPU、IFU/IEU/LSU、基础模块、配置和 RTL 生成入口。
- `src/test/scala`、`src/test/cpp`：模块、整机、复位与 Verilator 回归测试。
- `programs`：汇编、程序镜像、逐周期 trace 与验收元数据。
- `scripts`：本地 sbt 启动、验收程序生成和 RTL 验证脚本。
- [Chisel 编码规范](docs/chisel-coding-standard.md)及[配套 skill](.agents/skills/chisel-coding-standard/SKILL.md)。

教材位于上一级 `pipeline` 目录。历史报告、Git 历史、旧 RTL、波形及构建缓存未迁入。
`generated/`、`target/`、`test_run_dir/`、`.cache/` 均为可再生产物，由 `.gitignore` 排除。

## 构建与验证

版本保持 JDK 17、sbt 1.10.7、Scala 2.13.14、Chisel 3.6.1、chiseltest 0.6.2。
从本工程目录执行：

```bash
# 全部 Scala/Chisel 回归测试
make test SBT=./scripts/sbt-local.sh

# 生成默认 CPU RTL
make generate-cpu SBT=./scripts/sbt-local.sh

# 自动重新生成四种 CPU 配置，然后进行 12 次 Verilator 验证
make test-rtl SBT=./scripts/sbt-local.sh

# 仅运行整机验收和复位测试
make test-acceptance SBT=./scripts/sbt-local.sh
```

`scripts/sbt-local.sh` 默认复用工作区 `../../.tools/chisel`，在本工程建立独立缓存。
工具链可通过 `CHISEL_TOOLCHAIN_DIR` 指定；已有 sbt 在 PATH 中时可省略 `SBT` 参数。
RTL 验证需要 Verilator 和 C++ 编译工具，可用 `VERILATOR=/path/to/verilator` 指定。
RTL 中的镜像路径相对于本工程根目录，验证脚本会自动切换到该目录。

保留独立模块的 `generate-*` 入口与 CPU 的 `IMEM_DEPTH`、`DMEM_DEPTH`、
`INSTRUCTION_INIT_FILE`、`RESET_VECTOR`、`CPU_TARGET_DIR` 参数。例如：

```bash
make generate-cpu SBT=./scripts/sbt-local.sh \
  IMEM_DEPTH=128 DMEM_DEPTH=128 RESET_VECTOR=0x100 \
  INSTRUCTION_INIT_FILE=programs/rv32-configtest.memfile \
  CPU_TARGET_DIR=generated/riscv-single128
```

修改验收汇编后，运行 `make build-acceptance` 更新镜像、trace 和元数据；此命令需要
RISC-V GNU binutils，默认前缀为 `riscv64-unknown-elf-`，可用 `RISCV_TOOL_PREFIX` 指定。
直接运行现有测试和 RTL 验证使用保留的镜像，无需重新汇编。

## 后续流水线改造

当前整机限定 RV32，采用显式 `clk/reset` 和高有效同步复位。
测试中的逐周期预期对应单周期执行；实际加入流水级时需按新的延迟、停顿和冲刷行为调整。
程序镜像、指令覆盖和最终存储签名可继续作为流水线 CPU 的功能回归依据。
