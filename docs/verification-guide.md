# 后续开发者验证指导

统一入口和当前能力见[第七、八章更新](chapter7-8-verification-update.md)。本文以教材 §5.11.3（印刷页 229，PDF 页 264）的程序级 testbench 流程为基础；流水线依据 §7.2.2（321 页）和 §7.4（329 页），特权依据 §8.3（360 页）与 §8.4.5（373 页）。

## 选择验证层级

| 改动 | 首选测试 | 必须补充的整机证据 |
| --- | --- | --- |
| 纯组合或局部状态模块 | ScalaTest/chiseltest，独立预期 | 模块被流水线实际使用的程序 |
| 前递、停顿、冲刷、写回 | 既有 Pipeline*Spec + 观察层断言 | 合法 RV32I 定向程序与逐提交差分 |
| CSR、异常、中断、WFI | Chapter8TestPlanCases + 有序事件 | 四配置适用性和精确副作用检查 |
| 软件平台/编译/完成协议 | Python 构建器测试、检查器负向测试 | Spike 参考、CPU 真读回、完整观察窗口 |
| 性能优化 | 正确性回归先通过 | 相同工具/选项/输入的周期比较，保留测量边界 |

预期值必须来自规范、人工推导、独立模型或固定上游签名。不能把 DUT 输出转存成“预期”。新增模块测试放在 `src/test/scala`；生产生成器不依赖测试代码。复用 `TestEncoding` 和既有用例生成器，避免另一套编码常量产生不同语义。

## 新增整机程序

1. 选择稳定 ID，并写明教材依据、硬件能力、风险和检查位置。RV32I 使用 `programs/validation` 平台启动/收尾和链接脚本；第八章优先扩展 `Chapter8TestPlanCases.scala`，保留原 ID。
2. 先明确被读取的全部寄存器和内存初值，再写激励。初始化必须由共同 ELF 中的普通指令执行，不能只给 C++ 影子内存赋值。对运行中复位，检查控制状态恢复，不能假设 RAM 清零。
3. 独立计算期望寄存器/访存结果。特权程序同时计算 EPC、cause、tval、状态栈、pending 和年轻副作用的取消。允许陷阱时更老 W 指令完成，禁止一律把“trap 同拍写回”当错误。
4. 把 ELF/镜像、参考、检查器、完成协议、周期上限与覆盖点接入原适配器，再接入统一 manifest。第八章还必须审慎更新 `programs/verification/chapter8-required.json` 的独立用例清单；汇总器将它与导出的 manifest 对照，删除 manifest 条目不能静默漏测。新增可选功能必须有配置适用条件，缺工具不是 `not_applicable`。
5. 先跑单项/冒烟，再跑 all/full。差分或定向程序使用 seed 1/17/2026，每个种子同实例复位两次；保留每轮 trace。给检查器注入一种能被最终签名覆盖掉的中间错误，确认逐提交比较仍失败。

统一条目模板（字段值须替换为真实产物，不应直接作为可运行命令）：

```json
{
  "id": "stable-case-id",
  "chapter": 7,
  "suite": "arch",
  "config": "default",
  "elf": "run/case/program.elf",
  "image": "run/case/program.memfile",
  "reference": "run/case/reference.signature",
  "check": "signature-and-commit-diff",
  "completion": "indexed-readback, length, completion-store, eight-cycles",
  "cycleLimit": 1000000,
  "coveragePoints": ["stable-functional-point-id"]
}
```

运行结果另外记录 `seed`、`repeat`、`status`、`reason`、`exit`、`log`、`reproduce`。用例 ID、配置、seed、repeat 的组合必须唯一；补跑保留原失败及 `resolved_by` 等关联，不删除旧记录。

## 新增平台激励与参考模型

异步外设输入先定义可复现的同步采样边界。第八章现有驱动通过实际提交的标记 store 拉高/清除 IRQ，WFI 场景使用明确延迟；激励文件与循环上限随运行保存。时间源应由测试平台提供确定序列。测试 pending、局部使能、全局使能、委派和当前权限的组合；IRQ 到达与接受之间必须区分。

本地 CVW 路径为 `/home/unlastingstar/cvw`，第八章参考位于 `tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/`。移植保留原指令与向量；地址、外设或 CSR 能力适配必须记录修改理由与独立预期。缺 MMU/PMP/外设等才可按能力边界列不适用，不能以“参考跑不过”跳过。

接入新参考模型时保存版本、ISA、内存图、CSR 配置、启动状态与生成命令。RV32I 提交比较必须严格顺序，不能靠跳过记录“对齐”。模型不支持的 CSR、读时间或 IRQ 到达时机需先建立一致契约；尚未建立时继续使用有序事件/签名检查，不宣称完整特权差分。

## 签名观察位置与完成协议

当前无缓存 DTIM：普通 CPU `LW` 观察内存，经普通 store 协议回传。第七章保留 `0x8021ff20/24/28` 输出索引/值/字数，完成为 `0x8021ff00`，CoreMark 测量为 `0x8021ff10`。链接脚本保留这些地址，签名、栈和协议不能重叠。每个字节必须被程序初始化，后续 store 重建与真实 load 读回分别检查。

未来加入写回缓存时，CPU load 可能读到脏 cache line，外部 RAM 可能仍是旧值。必须先明确验收观察点：若验收 CPU 可见值，使用 CPU load 读回；若验收外部持久写回，增加 cache clean/flush、等待总线响应及内存侧检查。不能用直接窥探 RAM 代替二者，也不能把没有 flush 的外部旧值误判为架构错误。教材 §10.1.4（434 页）和 §10.1.8（441 页）解释相关写策略和 flush/invalidation。

完成不是“出现第一个预期 store”。必须检查全部索引的唯一顺序、长度、完成状态，以及完成后的八拍。该窗口用于发现迟到 store、重复完成和额外协议事件；终止循环内合法普通提交不会自行导致失败。窗口不足、没有完成、没有初始化签名字节均必须失败。

## 功能覆盖与动态断言

每个覆盖点记录稳定 ID、教材依据、适用配置、激励、采样表达式、检查器和命中证据位置。当前 RV32I 注册表为 `verification_coverage.required_bins()`，第八章为 `privileged_coverage.required_bins(config, profile)`；后者检查各配置的真实 `csr.op*`、`trap.cause*.from*.to*.interrupt*`、`interrupt.pending*.global*.accepted*.mode*`、`memory.<endian>.<load|store>.width*.offset*` 等事件。架构点在提交采样；前递、停顿、冲刷在真实发生处采样。要覆盖依赖距离，应按实际生产者/消费者寄存器和提交/执行历史计算，不以汇编源文件间隔代替；前递源必须来自真实选择信号。

必达 bin 是验收条件，不是报告装饰。新增 bin 应有最小正例和反例；功能点表应明确未命中、不适用及原因。失败运行的 bin 命中可辅助调试，但与通过运行分开保存。代码 line/toggle 与功能覆盖分别报告，不把测试数量、用例名称或“程序含该指令”计为命中。

动态断言检查有效位与写使能一致、停顿保持、冲刷取消、M/W 年龄对齐、退休唯一性和 x0。错误发生后保留首差前后周期；不要移除断言来使程序通过。当前观察包装层的生产公共输出一致性检查与原顶层接口检查也应保留。

## 当前可运行命令

```bash
make test-verification SBT=./scripts/sbt-local.sh
make test-verification SBT=./scripts/sbt-local.sh VERIFY_SUITE=privileged VERIFY_PROFILE=full
make test-diff SBT=./scripts/sbt-local.sh VERIFY_PROFILE=full
make report-verification SBT=./scripts/sbt-local.sh
PYTHONPYCACHEPREFIX=target/cache/python python3 -m unittest discover -s src/test/python -p test_verification.py -v
```

旧独立入口可以用于缩小问题。统一回归使用新目录，记录的 `reproduce` 默认重新构建；`reproduceOriginal` 是原始精确命令，依赖原目录中的镜像和二进制。生产程序的镜像目录各自独立；负向检查会使用专用 scratch 目录，重放这类检查时仍应避免并发覆盖同一个 scratch。

## 失败定位与证据清单

按以下顺序排查，保留原失败目录后再补跑：

1. 从 `summary.json`、`results.json` 定位首个非 pass 状态。区分构建/平台错误、CPU 错误、超时和未执行；检查原始退出码与日志是否矛盾。
2. 查看 `commands.jsonl`、工具版本和运行前后源码哈希。确认 ELF、编译选项、ISA、reset vector、存储深度和镜像属于同一次运行。
3. 对签名错误，依次比较参考、store 重建和 CPU 读回，检查索引、长度、掩码、地址映射和已初始化字节。
4. 对提交错误，看首差 PC、指令、反汇编符号、rd、地址、宽度/写字节及 RTL 周期。向前找对应 M 接受沿，不只查看最终签名。trace 中间删除、重复或错序不允许重新同步掩盖。
5. 对特权错误，沿更老 W、故障 M、年轻 E/D 的年龄顺序检查副作用，随后检查 cause/EPC/tval、pending、enable 和 xRET。
6. 对覆盖缺口，确认用例真的执行且通过，再看采样条件；不可通过删必达 bin 或使用失败运行命中修正报告。

评审应具备：源码快照与哈希；工具版本；完整 manifest 与适用性；ELF/反汇编/镜像；参考推导；实际签名；原始提交及流水线日志；首差报告；功能覆盖与 line/toggle 原始数据；命令和原始退出码；每个复位轮次的记录；负向检查结果；`SHA256SUMS`；具体归档运行 ID。

性能报告只使用定义好的测量区间，保留编译选项、数据大小和迭代数；正确性失败时不接受性能数字。CoreMark 当前记录 CRC、错误数、迭代数和周期，不据此宣称官方认证得分。更改频率假设、仿真 wall time 或测试循环次数不能替代等条件比较。

## 目录与清理

文档导航见[文档索引](README.md)。`docs/` 只保存 Markdown；原始结果、日志、源码快照和校验清单留在 `target/`。报告发布只复制 Markdown，并调整相对证据链接。

`make clean SBT=./scripts/sbt-local.sh` 删除整个 `target/`，包括运行证据与本地缓存。报告正文仍保留，证据清理后需重新运行报告命令；新运行使用新的时间戳目录。
