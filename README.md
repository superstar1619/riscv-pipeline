# RV32 五级流水线 CPU（Chisel）

教材配套 RV32 CPU，采用 F/D/E/M/W 五级流水线。`RiscvPipeline` 提供 RV32I，
`PrivilegedPipeline` 增加 CSR、精确异常、中断委托、U/S/M 模式、xRET、WFI、计数器和数据大小端。
目前不包含乘除法、MMU/PMP、浮点、缓存及 CLINT/PLIC 本体。

## 环境与快速开始

工具版本：JDK 17、sbt 1.10.7、Scala 2.13.14、Chisel 3.6.1、chiseltest 0.6.2。
从本项目根目录执行：

```bash
make help
./scripts/sbt-local.sh compile 'Test / compile'
make test-verification SBT=./scripts/sbt-local.sh
make generate-cpu SBT=./scripts/sbt-local.sh
```

本地启动器默认复用 `../../.tools/chisel`；可设置 `CHISEL_TOOLCHAIN_DIR`。
RV32 验收镜像在测试编译前自动生成，需要 RISC-V GNU binutils，默认前缀为
`riscv64-unknown-elf-`，可设置 `RISCV_TOOL_PREFIX`。
完整程序验证还需要 Verilator、Spike 及 CVW 的固定版本测试源码；`CVW_ROOT` 可指定 CVW 目录。

## 验证与报告

```bash
# 默认 all/smoke
make test-verification SBT=./scripts/sbt-local.sh
# 全部套件及配置，并发布 Markdown 报告
make report-verification SBT=./scripts/sbt-local.sh
# 只查看完整调度
python3 scripts/run/verify.py --suite all --profile full --plan
# 删除全部生成产物和原始运行证据
make clean SBT=./scripts/sbt-local.sh
```

`docs/` 只保存 Markdown。完整结果、日志、校验清单和源码快照位于 `target/`，
报告链接指向对应运行证据。`make clean` 清理整个 `target/`，报告正文保留；
清理后需要重新运行报告命令生成新证据。历史报告的结论只对应当次源码和运行。

## 目录与开发入口

| 目录 | 内容 |
| --- | --- |
| `src/main/scala/riscvpipeline/` | IFU、IEU、LSU、冒险控制、特权模块与 `generators/` 生成器 |
| `src/test/` | Scala、Python、独立 C++ RTL 验证；`legacy/` 保留单周期历史测试 |
| `programs/` | 程序源码、链接脚本、配置和固定测试输入 |
| `scripts/build/`、`scripts/run/` | 镜像/RTL 预处理与验证调度 |
| `scripts/report/`、`scripts/lib/` | 报告生成与共享检查器、覆盖统计 |
| `docs/` | 设计说明、开发指导与 Markdown 报告 |
| `target/` | RTL、测试工作目录、镜像、缓存和运行证据，全部可清理 |

生成器入口为 `riscvpipeline.generators.Generate*`，参数顺序保留；Make 目标和参数继续可用。
默认 RTL 输出为 `target/generated/`，测试工作目录为 `target/test-runs/`，本地缓存为 `target/cache/`。
外部指定的输出路径和 `CHISEL_CACHE_DIR` 仍按调用者设置使用。

完整导航见[文档索引](docs/README.md)，接口见[时序与接口](docs/interfaces.md)，
开发遵循[Chisel 编码规范](docs/chisel-coding-standard.md)。
单周期基线保存在 `baseline/single-cycle` 分支。
