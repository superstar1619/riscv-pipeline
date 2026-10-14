> 手写历史实施/验收记录。适用版本、日期与验证范围见正文；当前运行结果从报告导航进入。

# 第七章完整验收、§7.4 与 CoreMark

统一验证入口、真实签名读回和提交差分见[验证更新](../../development/chapter7-8-verification-update.md)；
后续开发见[验证指导](../../development/verification-guide.md)和[路线图](../../development/verification-roadmap.md)。
下文既有结果属于原报告运行，不能替代新入口的本轮验收证据。

性能测量口径、派生指标、功能验证分析和永久证据快照见
[完整性能与正确性报告](chapter7-performance-correctness-report.md)。

用户在五级流水线定向验证之后要求继续，并明确本轮连同 CoreMark 一起完成。
本轮保持生产硬件、Chisel 3.6.1 和观察接口，恢复整机验收入口，完成完整活动 Scala
回归、四种 RTL 配置验收、教材 §7.4 所述架构签名测试和 CoreMark 功能/周期验证。

## 可重复命令

在 `riscv-pipeline` 目录执行：

```bash
make test SBT=./scripts/sbt-local.sh
make test-acceptance SBT=./scripts/sbt-local.sh
make test-rtl SBT=./scripts/sbt-local.sh
make test-arch SBT=./scripts/sbt-local.sh
make test-coremark SBT=./scripts/sbt-local.sh
# 或合并运行全部架构测试和 CoreMark：
make test-validation SBT=./scripts/sbt-local.sh
```

架构/CoreMark 入口从源码重新构建软件，使用 Spike 生成参考签名，生成 RTL 后执行
Verilator。默认随机种子为 `1 17 2026`，可通过 `VALIDATION_SEEDS` 覆盖。
需 RISC-V GNU GCC/binutils（含 RV32I/ilp32 libgcc）、Spike、Verilator 及原 Chisel
工具链；本轮使用 GCC 15.2.0、Spike 1.1.1-dev、Verilator 5.036。软件统一以
`-O2 -march=rv32i -mabi=ilp32` 编译，完整参数记录在生成的 manifest。

上游源码来自 `CVW_ROOT`（默认 `/home/unlastingstar/cvw`）的 `addins`：

| 来源 | 固定 Git revision |
| --- | --- |
| riscv-arch-test | `c8c813af55ea79acb97fe832c5737cc4539af3ee` |
| CoreMark | `f3e8f2e0941e42961aadcc52750b1b5577c157c9` |

构建检查实际 revision 和源码字节；CoreMark 六个受保护文件与固定 Git blob 完全
一致，并记录 SHA256。上游 `coremark.md5` 早于后续上游修正，不能用于本 revision
的字节一致性判断。源码不复制进本工程，也不改写外部 checkout。
工具路径可用 `RISCV_TOOL_PREFIX`、`SPIKE`、`VERILATOR` 指定。

## 整机回归

| 验证入口 | 结果 |
| --- | --- |
| 完整活动 Scala 测试 | 14 suites，122/122 通过 |
| test-acceptance | 2 个整机验收 + 6 个 smoke，8/8 通过 |
| test-rtl | 4 配置 × 3 随机种子 × 2 复位启动，通过 |
| §7.4 架构测试 | 39 程序 × 3 随机种子，通过 |
| CoreMark | 2 模式 × 3 随机种子，通过 |
| 上游清单完整性回归 | 4/4 通过，缺失源码/头文件及未知名称均拒绝 |

Scala 和 RTL 整机验收只从历史 `.trace` 提取有顺序的架构访存事件，逐一检查地址、
读写、Funct3、实际掩码及 store 数据。气泡不计事件，额外、缺失或乱序事件失败。
两种验收配置在 300 周期内跑完完整事件序列，再检查固定签名；每次复位后重跑。
教材程序严格检查 `96=7`、读回 96、`100=25`；扩展配置检查非零复位向量和容量回绕。
完整活动测试包含原 18 项流水线冒险/存储/跳转/运行中复位检查。
旧单周期逐周期检查仍归档在 `src/test/legacy`，不作为流水线周期预期。

## 大型软件测试平台

| 项目 | 配置 |
| --- | --- |
| IROM | 524288 个 32 位字（2 MiB），代码从 `0x80000000` 开始 |
| DTIM | 32768 个 32 位字（128 KiB），平台范围 `0x80200000..0x8021ffff` |
| .data/.rodata | `0x80210000` 开始 |
| .bss 与栈 | .bss 结束必须小于 `0x8021c000`，栈顶 `0x8021f000` |
| 完成状态 | `0x8021ff00` 实际 SW：1 成功，3 失败 |
| CoreMark 测量标记 | `0x8021ff10` 实际 SW：1 开始，2 结束 |
| 周期上限 | 架构程序 1,000,000；CoreMark 每模式 30,000,000 |

JAL 上游边界布局的 text 为 1,756,256 字节，所以测试 IROM 使用 2 MiB。
这只是生成参数，没有改变默认 CPU 配置。生产 RAM 仍没有初始化/复位功能。
启动代码根据 ELF 的 `.data` 生成普通 SW 初始化序列，清零 `.bss` 并建立栈；
初始化代码放在 `.text` 后，二次链接核对数据内容完全相同，防止地址搬移。
实际执行指令逐条审计，拒绝压缩、M、CSR 和其他未支持指令。
GCC 的裸机参数关闭空指针检查删除，避免为不可达路径引入 `ebreak`；
乘除法由 RV32I libgcc 软件函数实现。

Spike 与 DUT 使用同一个 ELF/初始化程序。Verilator 从实际允许的 M 级 byte-mask
store 重建签名，检查每个字节确实写入，再逐字与完整参考比对。参考长度依据 ELF
的 `begin_signature/end_signature`，启动/编译/Spike/仿真超时和失败均传播。
每个程序各启动三个独立随机初态进程；禁止依赖上次程序的 RF/RAM 内容。
错误签名及 1 周期上限的负向测试均被检查器拒绝。

构建、ELF、反汇编、参考、镜像、逐 seed 日志和 JSON 报告在忽略的
`target/validation` 下；`*-results.json` 包含 revision、编译参数、每个程序的签名
范围和执行周期。`target/generated/validation` 顶层保持原 8 端口及位宽，6 个状态更新块
均为 `posedge clk`，同步 IROM/DTIM 与原复位契约保持一致。

## §7.4 覆盖与结果

执行固定版本 `riscv-test-suite/rv32i_m/I/src` 的全部 38 个文件：37 个算术、逻辑、
移位、分支、跳转和对齐访存指令测试，以及 FENCE 顺序访存场景。另执行教材
表 7.10 的 `misalign1-jalr-01`，它在当前上游版本位于 `privilege/src`，测试 JALR
清除目标最低位，无需 trap/CSR。共 39 程序、12,490 个 `inst_N` 生成向量，加一个
独立 FENCE 场景；每 seed 比对 12,569 个 32 位签名字（含 canary），117 次仿真
全部通过，三个 seed 的周期完全一致。当前版本 ori 有 409 个向量，教材表列 560；
因此总用例为 12,491，而非教材 2022 年统计的 12,642。

源码和环境头清单从固定 Git tree 获取，核对每个文件存在且字节一致；未知选择
名称失败。四项 Python 回归先复现原脚本的静默漏测，再验证缺失测试、缺失头文件
（包括已有旧拷贝）及混合有效/错误名称均拒绝，并验证完整清单和教材附加用例。

原始测试向量和宏保持完整。平台不定义 trap/instret 框架，关闭环境头中的压缩对齐
填充（`.option rvc` 改为 `.option norvc`），其余与上游固定版本一致。
FENCE 在当前单核、顺序 DTIM、无外部 I/O 的系统中无附加操作，测试验证 store 后
load 正确读取；这不代表已实现多核或外部总线的屏障协议。
§7.4 的对齐访问之外，当前 Scala 定向测试保留未对齐访问不写入/返回零的项目契约。
这轮没有增加 ECALL/EBREAK、特权、异常、CSR、中断、FENCE.I 或扩展 ISA，也不
据此声称通过全 ISA 认证、综合或物理时序收敛。

| 上游程序 | inst_N 向量 | 签名字 | 完成周期（各 seed 相同） |
| --- | ---: | ---: | ---: |
| add-01 | 588 | 590 | 7,122 |
| addi-01 | 561 | 563 | 5,968 |
| and-01 | 584 | 586 | 7,075 |
| andi-01 | 562 | 564 | 5,975 |
| auipc-01 | 64 | 66 | 2,716 |
| beq-01 | 583 | 585 | 11,787 |
| bge-01 | 590 | 592 | 12,457 |
| bgeu-01 | 726 | 728 | 14,782 |
| blt-01 | 580 | 582 | 12,231 |
| bltu-01 | 726 | 728 | 14,726 |
| bne-01 | 584 | 586 | 12,869 |
| fence-01 | 0 | 3 | 2,207 |
| jal-01 | 32 | 34 | 3,939 |
| jalr-01 | 33 | 35 | 3,363 |
| misalign1-jalr-01 | 1 | 3 | 2,227 |
| lb-align-01 | 33 | 35 | 2,820 |
| lbu-align-01 | 32 | 34 | 2,801 |
| lh-align-01 | 32 | 34 | 2,801 |
| lhu-align-01 | 32 | 34 | 2,801 |
| lui-01 | 63 | 65 | 2,518 |
| lw-align-01 | 32 | 34 | 2,801 |
| or-01 | 587 | 589 | 7,118 |
| ori-01 | 409 | 411 | 4,971 |
| sb-align-01 | 70 | 72 | 2,925 |
| sh-align-01 | 71 | 73 | 2,932 |
| sll-01 | 89 | 91 | 2,865 |
| slli-01 | 88 | 90 | 2,768 |
| slt-01 | 584 | 586 | 7,096 |
| slti-01 | 560 | 562 | 5,958 |
| sltiu-01 | 699 | 701 | 6,859 |
| sltu-01 | 722 | 724 | 8,188 |
| sra-01 | 90 | 92 | 2,870 |
| srai-01 | 87 | 89 | 2,760 |
| srl-01 | 92 | 94 | 2,897 |
| srli-01 | 90 | 92 | 2,778 |
| sub-01 | 592 | 594 | 7,145 |
| sw-align-01 | 68 | 70 | 2,902 |
| xor-01 | 588 | 590 | 7,117 |
| xori-01 | 566 | 568 | 5,997 |

FENCE 行的 `inst_N=0` 表示它是手写场景，不表示跳过测试。

## CoreMark 结果

使用未改动的上游 `iterate` 及 list/matrix/state/util 算法，由功能驱动按上游
`core_main` 初始化；单上下文、2000 字节静态数据、10 iterations，分别执行性能
和验证种子。测量在真正执行 `iterate` 的开始/结束 store 标记之间，排除启动数据
初始化及 CRC 检查；包括标记间的函数调用开销。没有向核心增加 CSR 或计时器。

| 模式 | seed CRC | list CRC | matrix CRC | state CRC | 最终 CRC（Spike 参考） | errors |
| --- | --- | --- | --- | --- | --- | ---: |
| coremark-performance | e9f5 | e714 | 1fd7 | 8e3a | fcaf | 0 |
| coremark-validation | 18f2 | e3c1 | 0747 | 8d84 | c64e | 0 |

前四个 CRC 同时核对上游 2K 模式的固定参考；最终 CRC、errors=0、iterations=10
逐字与独立 Spike 签名一致。两种模式在三个随机 seed 下结果和周期均完全一致。

| 模式 | 完成周期（含启动和检查） | 测量执行周期 | cycles/iteration |
| --- | ---: | ---: | ---: |
| coremark-performance | 10,558,861 | 10,533,211 | 1,053,321.1 |
| coremark-validation | 11,357,303 | 11,331,447 | 1,133,144.7 |

这是固定迭代的功能验证和仿真周期测量。未运行上游自动校准/输出的 timed main，
没有真实时钟频率或至少 10 秒的有效测量，因此不报告官方 CoreMark 得分。
功能驱动、计时方式和编译参数随代码保留，可在以后有真实时间源时继续正式计分。

## 交付状态

最终 `test-validation` 合并入口为 41 程序 × 3 随机种子，123/123 通过；错误签名
及超时检查均通过。只读分支审查无 Critical，发现的一项源码清单完整性问题已
修正，四项回归由失败转为通过，再运行全部软件验证确认结果。

所有入口通过后保留 `feat/chapter7-pipeline`，不合并、不推送。
本轮验证没有要求修改生产硬件。前一阶段的定向验证记录保留在
[第七章流水线实施记录](chapter7-pipeline.md)。
