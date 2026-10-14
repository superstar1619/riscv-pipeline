# 第七、八章 CPU 测试总报告

UTC 运行目录：`20261014T092004125326Z`。本轮结论：存在 277 条失败或未执行记录；详见逐例结果。

状态计数：`{"pass": 2348, "build_platform_error": 1, "cpu_failure": 276, "not_applicable": 1}`。源码前后校验：一致。

本报告只使用本目录本轮命令产物，历史报告不参与通过判定。全量 Scala 回归仅执行一次；独立测试计数以 Scala 测试名称、RTL 的配置/程序/种子/复位轮次分别呈现。`test-acceptance` 为重复入口核验，不加入独立用例计数。结果行不是互不重叠的功能覆盖数量。

第七章：2 MiB IROM、128 KiB DTIM、复位 0x80000000；39 个架构程序上限 100 万周期；CoreMark 性能/验证两模式各三个种子，2000 字节、10 次迭代、上限 3000 万周期。逐字比较 Spike 签名，同时检查官方 CRC、错误数和迭代数；报告仿真周期，不计算官方得分。

第八章：18 个 CVW RV32 原始样例，默认与 Sstc/HPM 配置；默认 WALLY-trap-01 显式不适用；其他 10 个上游样例逐项列于 chapter8-cvw/programs/coverage.md。64 KiB IROM/DTIM、复位 0；每配置每程序种子 1/17/2026，各同实例复位重跑两次，上限 100 万周期。CPU 真实 SW 初始化所有数据/查找表/哨兵，真实 LW 读回全部 1024 字签名及前后哨兵；核对使用长度、完成标记和后续八拍副作用。另执行既有四配置 RTL 与 Python 检查器回归。

适配说明：异常地址零改为 0x10000；ELF 符号重定位断点及非对齐地址；Bare medeleg 合法掩码为 0x3ff；CLINT/PLIC/GPIO 换成存储标记驱动同步引脚；默认 STIP 为软件置位，扩展使用 Sstc。异步处理器按 xepc 恢复；结束 ecall 保留原签名，委派到 S 的结束通过额外 M 软件中断进入平台收尾，该收尾中断不写测试签名。初始化 Sstc 比较器为全一。所有适配补丁与独立预期推导均随程序保存。

|CoreMark 模式|种子|状态|完成周期|测量区间周期|
|---|---|---|---|---|
|coremark-performance|1|pass|10558861|10533211|
|coremark-performance|17|pass|10558861|10533211|
|coremark-performance|2026|pass|10558861|10533211|
|coremark-validation|1|pass|11357303|11331447|
|coremark-validation|17|pass|11357303|11331447|
|coremark-validation|2026|pass|11357303|11331447|

逐例结果：results.json/csv；章节汇总：chapter7/、chapter8/；命令与退出码：各目录 commands.jsonl；复现：reproduce.sh；完整性：SHA256SUMS。
