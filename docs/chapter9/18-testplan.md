# 第九章整机 Test Plan 平台

平台程序、ELF 构建器、独立预期与检查器已完成；生产整机执行须读取本轮运行结果，
不能把 `--build-only` 当成通过。程序源位于 `programs/chapter9/`；不预加载 RAM。

|稳定用例 ID|独立检查|动态证据|
|---|---|---|
|memory-switch|有符号/无符号 byte/halfword、写掩码、RAM 代码安装、FENCE.I 后执行返回、混合本地 DTIM|真实子字提交、RAM 取指、返回 ROM 提交、bus wait、IFU/LSU 同时等待|
|apb-subword|scratch 0x11223344→0x1122aa44→0xbbccaa44；写次数严格为 3|APB 掩码 2/12、计数寄存器真实物理读值|
|privilege-endian|MBE 使数据 LW 读成 0x44332211、SW 0xaabbccdd 的物理字节为 aa/bb/cc/dd；ECALL cause=11、tval=0，返回后 0x99|真实 LW 写回、接受的异常事件与有序签名|
|interrupt-precise|MSwInt pending 后开启全局使能；cause=0x80000003、mepc=irq_resume、tval=0，返回后 0x99|已提交总线标记后在 committed_m/f 时拉高中断；实际中断接受事件|

程序仅数百字节；BootROM/local IROM 镜像均补齐 1024 个 32 位字，大小上限 4 KiB。
纯总线复位 0x1000；混合复位 0、local IROM/DTIM 各 4 KiB。RAM 为
0x80000000/64 KiB；APB scratch/count 为 0x10000000/+4。CPU 初始化所有读取的数据，
同实例第二次复位不假定 RAM 清零。RAM 可执行程序由 CPU store 安装在 0x80000100。

签名从 0x80008000 开始。真实 CPU `LW a0` 退休必须对应每个签名字，
然后架构 store 按顺序输出 index/value 到 0x8000ff20/24、长度到 0x8000ff28、
完成值 0x5a 到 0x8000ff00。检查器逐项比较全部架构 store，另按程序预期端序独立核对物理 AHB
完成写的有效 byte lane（架构原始源与物理复制/反转数据分开），拒绝中间错误、缺失、额外、重复、错序与完成后迟到 store；完成后观察八拍。
特权预期来自指令/CSR 语义与链接符号，APB 预期来自固定寄存器契约，均不来自 DUT。

## 命令与证据

```bash
# 查看完整 144 条必要运行记录
python3 scripts/run/test-chapter9-rtl.py --plan
# 仅构建与独立参考；144 条生产记录明确保持 not_executed，退出 1
python3 scripts/run/test-chapter9-rtl.py --build-only
# 生产及 test-only observer 就绪后执行全部矩阵
SBT=./scripts/sbt-local.sh python3 scripts/run/test-chapter9-rtl.py
# 小范围定位，仍覆盖两种模式 × RAM 延迟 0/1/2 × 同实例两次复位
SBT=./scripts/sbt-local.sh python3 scripts/run/test-chapter9-rtl.py --cases memory-switch --seeds 1
PYTHONPYCACHEPREFIX=target/cache/python python3 -m unittest discover -s src/test/python -p 'test_chapter9*.py' -v
```

输出为新的 `target/chapter9-testplan/runs/<UTC时间戳>/`，保存源快照、源码哈希、
命令/原始退出码、工具版本、ELF/反汇编、固定预期、全部实际 trace 与每轮复位检查。
`report-chapter9.py <运行目录>` 只依据该目录证据检查矩阵完整性，并使用现有
`register_report` 登记到 `target/reports/`。失败及负向证据不得覆盖成通过。
所有 sbt 命令使用 `flock target/chapter9-sbt.lock`。

Test-only 生成器入口为：
`Test / runMain riscvpipeline.GenerateChapter9Observer <rtlDir> <imagePath> <bus|mixed> <ramLatency> <apbLatency>`。
观察顶层为 `Chapter9Observer`，端口契约与生产核完全分离；适配器
`src/test/cpp/chapter9_rtl.cpp` 仅记录真实提交、架构 store、物理 AHB 完成、等待与 trap。

## 参考模型边界与明确未覆盖项

本地 Spike 保留 0x1000 reset ROM 和 0..0xfff debug 模块；直接装载原程序会冲突。
纯总线 `memory-switch` 的独立 Spike 参考将 ELF `.text` 搬到 0x80002000，
证明指令字节 SHA256 完全相同，然后严格比较从入口至完成的全部提交，无跳过/重同步。
仅将 ROM PC 与 JAL/JALR 指令产生的 PC+4 link 映回原地址；先验证原始 Spike 和
DUT link 均等于各自 PC+4，绝不归一化任意寄存器或内存结果。该证据是有界的地址
适配差分，不能称为“相同装载地址的完整差分”。混合本地 DTIM 位于 0x800，
与 Spike debug 模块冲突，其完整 Spike 差分保持未执行；MMIO/特权仍用独立预期。

原 CVW `ahb64_ram_{0/1/2}_{0/1}_rv64gc` 六组合及 `fadd_b11-01` 均未覆盖：
当前没有 RV64GC、浮点、缓存、burst。功能 bin 使用真实动态事件，不能由程序名称、
含有指令、最终通过状态推断。无 line/toggle 原始采集结果时不声称代码覆盖率。

## 平台检查点（生产执行前）

证据：`target/chapter9-testplan/runs/20261017T011000Z-platform-preflight/`。
八份 ELF 与 4 KiB 镜像构建成功；纯总线 memory-switch 的独立 Spike 参考生成
120 条至完成的提交，签名与人工预期一致。平台 Python 10 项通过；C++ 仅通过契约
stub 的语法检查，尚非生产 RTL 编译。全 Python 回归 93 项中 92 项通过；已有
`test_chapter8_checker.Chapter8CheckerNegativeTests.test_reject_signature_missing_extra_and_timeout`
因独立第八章 RTL checker 尚未构建而失败，原日志保留为 `python-full.log`。
全部 144 条生产记录明确为 `not_executed`；生产、负向实际 trace 与代码覆盖等待后续
运行，当前报告登记为未完成。后续补跑必须保留该检查点，不将它改写为通过。

## 最终整机验收（2026-10-17）

完整生产运行：`target/chapter9-testplan/runs/20261017T012308175224Z/`。
入口 `SBT=./scripts/sbt-local.sh python3 scripts/run/test-chapter9-rtl.py` 退出 0；
`summary.json` 的 `passed/fullMatrix/sourceStable/negativePassed/negativeIrqPassed/
negativeSpikePassed/codeCoveragePassed` 全为 true，`evidenceErrors` 为空。
四个独立程序 × 两种模式 × RAM 延迟 0/1/2 × 三个种子 × 同实例两次复位，
**144/144 条实际 CPU 记录通过**。八份 ELF/镜像、24 份观察顶层及全部运行证据均归档。

|配置|实际复位运行|line 命中/点数|toggle 命中/点数|
|---|---:|---:|---:|
|bus-only-ram0|24/24|11463/12104|80975/84068|
|bus-only-ram1|24/24|11473/12104|80976/84068|
|bus-only-ram2|24/24|11473/12104|80976/84068|
|mixed-ram0|24/24|11990/12680|84914/87512|
|mixed-ram1|24/24|12000/12680|84914/87512|
|mixed-ram2|24/24|12000/12680|84914/87512|

表中为本配置四个生成版本的 Verilator 原始覆盖点汇总，不是 Scala 唯一源码行数。
所有未命中点保留在各 `coverage/<config>/coverage.dat/info`，无排除。功能必达点通过
实际提交/物理完成/等待采样核验；无 APB 的 memory-switch 在 RAM 延迟 0 要求真实
RAM 完成且无 HREADY 低采样，在延迟 1/2 要求连续 HREADY 低至少对应拍数。时间契约
来自固定配置，运行与报告复核使用相同配置派生，不能从 DUT 结果推断期望延迟。

纯总线 memory-switch 的 18 次实际复位运行均通过 128 条提交的严格 Spike 比较；
本地地址适配边界保持如上，不声称混合模式或特权/MMIO 的完整 Spike 差分。
真实 passing trace 派生并保存七种 memory/protocol 错误、八种 IRQ 因果错误以及一种
最终签名不变的中间提交错误：前两组必须拒绝，中间错误须保留签名检查通过但被
Spike 提交比较拒绝。平台与独立报告器合计 22 项 Python 测试通过。

IRQ 验收要求恰好一次实际 pin rise，且 rise 时 committed_f/m 为真；接受陷阱时 pin
仍高且 committed_f/m 均为零；fall 必须在该陷阱接受沿之后。缺失、重复、晚到 rise、
提前/缺失 fall、pin 低接受或 committed 期间接受均失败。实际 pin 和 committed 证据
在同一同步采样边界记录，不能用独立存在的 coverage bin 代替因果顺序。

报告器绑定每个配置/程序/seed/reset 的唯一归档路径，重新解析原始 JSONL 并核对
实际事件与该轮记录完全一致，使用归档 oracle/checker 重跑签名、物理有效 byte lane、
trap/IRQ、窗口与 Spike 检查。它还独立检查平台命令退出码、源快照与前后哈希
（包括 `cvw_common.py/commit_diff.py/report_catalog.py`）、负向 trace 和覆盖原始文件，
不能仅凭缓存的 `check.pass=true` 接受旧目录、错误复位或外来文件。

前置失败均保留，未改写为通过：

- `20261017T010707109875Z`：IRQ 因果与证据复核强化前主动终止，退出 130，144 条未执行。
- `20261017T011724769456Z`：默认 ccache 在只读目录导致 18 条构建平台错误，126 条未执行；后续入口将缓存默认放到工作区 `target/cache/ccache`。
- `20261017T011958432347Z`：6 条 memory 通过、6 条 APB CPU 失败、132 条未执行。真实 SB+1/SH+2 的物理数据/掩码正确但 scratch 未更新，定位到 APB 寄存器按完整 byte offset 解码。修复 `e5fcccd` 按 `PADDR(31,2)` 解码，保留上位地址与 PSTRB；受影响 bank/Uncore/SoC 18 项测试及生成/lint 通过后，才进行本次完整重跑。

生产回归的全 Scala 688 项在该独立 APB 修复前通过；修复后受影响的 18 项重跑通过。
完整 Python 回归与其最终证据由第九章总报告记录。全部原始 RV64GC/fadd/cache/burst
未覆盖项和混合模式完整 Spike 缺口仍保留，不因当前矩阵通过而改变能力边界。
