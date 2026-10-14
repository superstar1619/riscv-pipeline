# 单周期独立源码快照

来源：原 `single-cycle/riscv-single-cycle` 项目，提交 `83dee1688105ea6b645f08cc5202827f71292e4a`。
导入日期：2026-10-14。源码、测试、脚本、构建配置、程序输入和 GTKWave 布局按字节复制，
原项目没有修改。本目录不加入当前流水线构建，也不作为运行输出目录。
[来源与文件指纹](provenance.json)、[SHA-256 清单](SHA256SUMS)记录所有冻结输入。

设计和手写历史记录已集中到[单周期文档](../../docs/chapter7/single-cycle/README.md)。
生成 RTL、日志和波形属于[可清理历史证据](../../target/reports/single-cycle/2026-10-14/report.md)。
默认整机为 RV32；独立辅助模块的 64 位配置不表示整机支持 RV64。

## 核对快照

从本项目根目录执行：

```bash
(cd references/single-cycle && sha256sum -c SHA256SUMS)
```

## 在 target 工作副本中复现

需要既有 JDK 17 / sbt 1.10.7 工具链、RISC-V GNU binutils 和 Verilator。
从本项目根目录执行；工作目录使用唯一名称，不覆盖已有运行。

```bash
project_root="$PWD"
mkdir -p target/baselines/single-cycle
run_dir=$(mktemp -d "$project_root/target/baselines/single-cycle/run.XXXXXX")
cp -a references/single-cycle/. "$run_dir/"
cd "$run_dir"
export CHISEL_TOOLCHAIN_DIR="${CHISEL_TOOLCHAIN_DIR:-$project_root/../../.tools/chisel}"
export CHISEL_CACHE_DIR="$project_root/target/cache/single-cycle-chisel"
mkdir -p "$project_root/target/tmp"
export TMPDIR="$project_root/target/tmp"
make build-acceptance
./scripts/sbt-local.sh test
make generate-cpu SBT=./scripts/sbt-local.sh
make generate-cpu SBT=./scripts/sbt-local.sh IMEM_DEPTH=128 DMEM_DEPTH=128 \
  CPU_TARGET_DIR=generated/riscv-single128 INSTRUCTION_INIT_FILE=programs/rv32-configtest.memfile RESET_VECTOR=0x100
make generate-acceptance SBT=./scripts/sbt-local.sh
make test-rtl SBT=./scripts/sbt-local.sh
make test-final-waves SBT=./scripts/sbt-local.sh
```

历史文档内相对命令也在这个工作副本执行。原文中的旧绝对路径仅表示历史位置，
不再要求原 single-cycle 目录存在。该流程在 target 中重新生成报告、镜像、RTL 和波形，
不会改写冻结输入或将单周期测试加入流水线回归。本次文档整理没有重跑上述硬件验收。
