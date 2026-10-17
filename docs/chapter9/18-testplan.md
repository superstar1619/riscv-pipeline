# 第九章整机 Test Plan 平台

平台程序、ELF 构建器、独立预期与检查器已完成；生产整机执行须读取本轮运行结果，
不能把 `--build-only` 当成通过。程序源位于 `programs/chapter9/`；不预加载 RAM。

|稳定用例 ID|独立检查|动态证据|
|---|---|---|
|memory-switch|有符号/无符号 byte/halfword、写掩码、RAM 代码安装、FENCE.I 后执行返回、混合本地 DTIM|真实子字提交、RAM 取指、返回 ROM 提交、bus wait、IFU/LSU 同时等待|
|apb-subword|scratch 0x11223344→0x1122aa44→0xbbccaa44；写次数严格为 3|APB 掩码 2/12、计数寄存器真实物理读值|
|privilege-endian|MBE 使数据 LW 读成 0x44332211；ECALL cause=11、tval=0，返回后 0x99|真实 LW 写回、接受的异常事件与有序签名|
|interrupt-precise|MSwInt pending 后开启全局使能；cause=0x80000003、mepc=irq_resume、tval=0，返回后 0x99|已提交总线标记后在 committed_m/f 时拉高中断；实际中断接受事件|

程序仅 148–292 字节；BootROM/local IROM 镜像均补齐 1024 个 32 位字，大小上限 4 KiB。
纯总线复位 0x1000；混合复位 0、local IROM/DTIM 各 4 KiB。RAM 为
0x80000000/64 KiB；APB scratch/count 为 0x10000000/+4。CPU 初始化所有读取的数据，
同实例第二次复位不假定 RAM 清零。RAM 可执行程序由 CPU store 安装在 0x80000100。

签名从 0x80008000 开始。真实 CPU `LW a0` 退休必须对应每个签名字，
然后架构 store 按顺序输出 index/value 到 0x8000ff20/24、长度到 0x8000ff28、
完成值 0x5a 到 0x8000ff00。检查器逐项比较全部架构 store，另核对物理 AHB
完成写，拒绝中间错误、缺失、额外、重复、错序与完成后迟到 store；完成后观察八拍。
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
