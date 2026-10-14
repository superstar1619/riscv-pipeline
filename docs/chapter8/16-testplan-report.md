# §8.3 TestPlan 实施与详尽报告

本轮以教材印刷页 360–366（PDF 页 395–401）、表 8.5–8.9、当前 Chisel RV32 核心与本地 CVW 特权源码为依据。
快照提交为 `5b1804fbbf0eb03636e19502e77e20340698e0c3`。本轮共享整机用例 **276 个配置实例**，Scala 每例复位重跑 2 次，独立 RTL 每例 3 种种子各重跑 2 次；共核对 **2208 次运行**的完整有序签名。
全量 Scala 回归 **590 项通过**，第七章 RTL 四组配置、三个种子共 12 次通过；最终失败、未执行均为 0。所有统计来自本轮实际产物。

## 1. 教材与CVW对照、平台适配

| 教材要求 | CVW对照 | 本轮检查与证据 |
| --- | --- | --- |
| 表8.5 ECALL/EBREAK/MRET/SRET | WALLY-trap-01/trap-s-01/trap-u-01 | EX00–EX11、PV01–PV06、IRRE；既有Chapter8AcceptanceSpec的嵌套委托/返回也全量复验 |
| 表8.5 WFI、表8.7 TW/TSR | WALLY-wfi-01/status-tw-01/trap-sret-01 | WF系列、WFWAKE、PV04；既有WFI/PrivDecode/PrivMode单元测试 |
| 表8.5 SFENCE.VMA | 教材尚未显式测试 | PV03：当前Bare实现不支持此指令，真实程序应产生cause=2 |
| 表8.6 CSR读改写、权限、只读、身份 | csr-permission-u/s-01/minfo-01/misa-01 | CS01–CS08及CSRAccess/CSR全量单元测试 |
| 表8.6 状态、委托、陷阱、向量、S视图 | mtvec-01/stvec-01/sie-01/trap-s-01 | CS03、IRVIEW、IR系列；CSRMachine/CSRSupervisor/CSRStatus |
| 表8.6 Counters原文尚未测试 | 本轮补充 | CT01/CTA/CTD/CTRET/CTCARRY/CTTIME/HPM1–5；CSRCounters |
| 表8.7 MBE/SBE/UBE/MPRV | WALLY-endianness-01 | EN系列及既有word/byte/half真实存储测试；CSRStatus/EndianSwap |
| 表8.8 11类非页异常 | WALLY-trap-01及U/S用例 | EX系列逐类检查EPC/cause/tval/status/pending与有序存储；PR01/PR02检查精确取消 |
| 表8.9 六类中断 | WALLY-trap-01、TEST-LIB-32.h | IR1/3/5/7/9/11的直接/向量、M处理/S委托，STIRQ补充Sstc产生STIP |
| 中断优先级、屏蔽、返回重入 | mie-01/status-mie-01/status-sie-01 | IRMASK/IRCROSS/IRPRI/IRRE，TrapSpec穷举全部非空来源子集与权限组合 |

CVW revision：`2f82f001139358be7646e910ff6fa2a140bed620`。本轮读取了RV32特权测试程序和TEST-LIB-32.h，沿用其陷阱签名思想与场景语义；未直接运行CVW的外设程序，也不声称与Spike/Imperas逐指令等价。
CVW的CLINT/PLIC/GPIO触发机制在本平台分别适配为同域中断输入、MIP/SIP软件位和Sstc比较；IROM/DTIM窗口之外用0x2000产生访问故障，地址0在本平台合法。
本核不含C扩展，因此额外执行取指未对齐测试；页面、PMP/PMA及原子异常遵循已确认的范围排除。

中断优先级预期独立核对[官方特权规范Machine-Level ISA 1.13](https://docs.riscv.org/reference/isa/priv/machine.html)：MEI、MSI、MTI、SEI、SSI、STI。S类的软件中断先于定时中断。

## 2. 基线、版本、命令与计时口径

仅将原有PipelineBase、Privileged、PrivilegedSpec三个修改文件提交为 `chore: snapshot before chapter 8.3 test plan`，提交后工作区干净。
`./scripts/sbt-local.sh compile test`：314/314通过，基线sbt测试命令总计138秒。原始日志见[baseline.log](../reports/chapter8-testplan/baseline.log)。本轮没有修改生产Scala源码或生产顶层端口。

工具：sbt 1.10.7、Scala 2.13.14、Chisel 3.6.1、chiseltest 0.6.2、Eclipse Adoptium 17.0.20.1 (see baseline/regression log)；Verilator 5.036 2025-04-27 rev v5.036；g++ (Ubuntu 15.2.0-16ubuntu1) 15.2.0；Python 3.14.4。
报告生成UTC时间：`2026-10-14T08:33:20.328372+00:00`；sbt日志时间采用Asia/Shanghai（UTC+8）。仿真周期只计解除复位后的上升沿；包含完成标记提交沿及之后8拍，不包括复位拍：Scala为3拍，RTL为3–5拍。
RTL两次运行使用同一DUT对象；DTIM不随复位清空，因此每个fixture都初始化它随后读取的数据。
未指定物理频率，周期不是秒数或性能评分。工具命令耗时见[commands.jsonl](../reports/chapter8-testplan/commands.jsonl)。

```sh
./scripts/sbt-local.sh compile test                      # 快照后基线
python3 scripts/report-chapter8.py --begin              # 最终验证起点
make test-chapter8-plan SBT=./scripts/sbt-local.sh        # 共享Scala用例
make test-chapter8-rtl SBT=./scripts/sbt-local.sh         # 四配置lint/build/仿真/负向检查
chapter8_run=$(python3 scripts/report-chapter8.py --path)
env -u CHAPTER8_CASE_FILTER JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx3g' ./scripts/sbt-local.sh   "set Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, \"-u\", \"$chapter8_run/junit\")" compile test   > "$chapter8_run/regression.log" 2>&1
make test-rtl SBT=./scripts/sbt-local.sh > "$chapter8_run/chapter7-rtl.log" 2>&1
python3 -m unittest discover -s src/test/python -v > "$chapter8_run/python.log" 2>&1
python3 scripts/report-chapter8.py                       # 验证证据、归档、生成本报告
# 一键重新测试并更新报告：
make report-chapter8 SBT=./scripts/sbt-local.sh
```

最终完整回归日志见[regression.log](../reports/chapter8-testplan/regression.log)，第七章见[chapter7-rtl.log](../reports/chapter8-testplan/chapter7-rtl.log)。
`scripts/test-chapter8-rtl.py`导出共享清单；每个配置用GeneratePrivilegedPipeline生成生产RTL，执行`--lint-only -Wall`和`--cc --exe --build`。
仅关闭既有DECLFILENAME/UNUSEDSIGNAL警告分类，lint/build的全部输出归档于`rtl/<配置>/`。
实际逐个命令含绝对路径、返回码和耗时，记录于commands.jsonl；源码与CVW/教材SHA-256见[metadata.json](../reports/chapter8-testplan/metadata.json)。
最终源码HEAD和工作区状态在metadata.json中记录；新增测试/驱动/报告属于该快照上的可审阅工作区变更，原始快照与本轮变更分离。

## 3. 四配置与检查协议

| 配置 | S | 大端 | Sstc | 活动HPM | 复位地址 | IROM/DTIM字数 | WFI阈值 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| default | 开 | 开 | 关 | 0 | 0 | 2048/2048 | 24 |
| minimal | 关 | 关 | 关 | 0 | 0 | 2048/2048 | 24 |
| sstc-hpm | 开 | 开 | 开 | 3 | 0 | 2048/2048 | 24 |
| reset100 | 开 | 开 | 关 | 0 | 0x100 | 2048/2048 | 24 |

所有适用RTL用例执行种子1/17/2026，每个种子在同一对象上复位重跑。种子控制未初始化状态随机化、复位长度与外部输入到达延迟；不产生随机的期望值。
程序在地址600写入激励标记，驱动在随后9+(seed mod 3)拍、下降沿后稳定阶段设置输入。此时WFI保持，唤醒后固定后继指令作为IRQ的EPC。
处理程序向地址604写应答标记，驱动在该标记提交沿后撤销输入。TIME始终为明确的64位值，STIRQ从0变为0x100000020，应答后回到0。

存储在上升沿提交之前采样，检查地址、原始WriteData和ByteMask的全部有序事件（包括初始数据、陷阱日志、激励/应答和完成标记）。
地址512写0x5a完成；签名区从1024起每条陷阱五个字依次记录EPC、cause、tval、status、pending。
S陷阱记录SSTATUS/SIP，M陷阱记录MSTATUS/MIP；因此S签名不包含机器状态位。
非法、缺失、额外或错序存储均失败，每轮上限10000拍。完成后继续观察8拍，以检查迟到副作用。
Scala只通过输入驱动程序，不直接修改内部寄存器；RTL驱动只连接生产顶层端口。

异常触发：EX00执行JAL到PC+2（EPC=源PC，tval=目标）；EX01跳转0x2000（EPC/tval均为故障取指地址）；EX02执行0xffffffff（tval=指令）；EX03执行EBREAK（tval=PC）。
EX04/06执行地址2的LW/SW（tval=2）；EX05/07执行地址0x2000的LW/SW（tval=0x2000）；EX08/09/11通过真实MRET进入U/S/M后执行ECALL（tval=0）。
所有异常夹具在故障前写入老指令结果，保留目的寄存器哨兵，并紧跟年轻poison store；处理程序跳过故障/poison后存入寄存器结果，从完整存储序列验证精确提交。
CTRET以5条正常指令、14条处理程序指令及2条恢复/抑制指令推导instret=21，故障与被取消指令不计；CTCARRY以明确半字写和3条退休指令推导instret高半8、低半1。
HPM1–5分别选择退休、加载、存储、LoadStallD、StoreStallD来源；受控主体有7次退休、2次加载、2次存储、2次加载结果依赖、2次相邻存储/加载冲突，停止后以NOP隔离年轻CSR冒险。

共享清单与独立预期见[manifest.tsv](../reports/chapter8-testplan/manifest.tsv)、[cases.json](../reports/chapter8-testplan/cases.json)。
每个用例的2048字镜像、events以及完整实际签名均归档。`results.json/csv`保留每层、每种子、每次复位的周期和结果。

## 4. 汇总：适用项通过率

| 配置 | 适用配置实例 | Scala运行通过/失败 | RTL运行通过/失败 | 按ID并集统计不适用 | 未执行 |
| --- | --- | --- | --- | --- | --- |
| default | 74 | 148/0 | 444/0 | 12 | 0 |
| minimal | 45 | 90/0 | 270/0 | 41 | 0 |
| sstc-hpm | 83 | 166/0 | 498/0 | 3 | 0 |
| reset100 | 74 | 148/0 | 444/0 | 12 | 0 |

| 功能 | 适用配置实例 | Scala运行 | RTL运行 | 失败 | 未执行 |
| --- | --- | --- | --- | --- | --- |
| exception | 43 | 86 | 258 | 0 | 0 |
| precise | 8 | 16 | 48 | 0 | 0 |
| csr | 25 | 50 | 150 | 0 | 0 |
| privileged | 37 | 74 | 222 | 0 | 0 |
| counter | 35 | 70 | 210 | 0 | 0 |
| interrupt | 106 | 212 | 636 | 0 | 0 |
| endian | 18 | 36 | 108 | 0 | 0 |
| optional | 4 | 8 | 24 | 0 | 0 |

适用项运行通过率为100%；不适用ID来自配置专属用例（包括minimal的非法能力探测），不计入分母。逐ID适用性见[applicability.json](../reports/chapter8-testplan/applicability.json)。
验证层级：叶模块/组合边界和内部提交由本轮全量Scala复验，整机由共享程序Scala 552次和独立RTL 1656次检查。RTL通过率不包含生成/lint，也不包含检查器故意失败。

| 明确不适用能力 | 原因 |
| --- | --- |
| 取指/加载/存储页异常 | 无MMU，Bare模式 |
| PMP保护和可配置PMA | 无PMP/总线属性管理；TIM窗口边界访问故障仍已检查 |
| 浮点CSR、FS/SD与FPU异常 | 无浮点单元，相关位固定零 |
| 原子扩展及AMO异常 | 无A扩展 |
| CLINT、PLIC、GPIO硬件 | 本核无外设；同域输入和CSR触发覆盖中断路径 |
| RV64 SXL/UXL、向量VS/XS | 固定RV32，无向量/附加扩展 |

## 5. 逐用例预期、实际、周期与陷阱签名

每行是一个稳定用例编号在一个适用配置上的实例。预期写事件数量包含完成标记；实际各次运行都逐项匹配且签名差异为0。
周期列为Scala两次运行的范围、RTL六次运行的范围。每行链接的events为独立预期，RTL链接含两次复位的实际完整签名（种子1）；种子17/2026及Scala记录见results.json。
陷阱列依序列出 `(epc,cause,tval,status,pending)` 的十六进制值。

| 配置/编号 | 行为、教材表、CVW对照 | 预期→实际写数/差异 | Scala拍 | RTL拍 | 陷阱记录 | 证据 |
| --- | --- | --- | --- | --- | --- | --- |
| default-EX00 | 非页异常 cause=0 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(1c,0,1e,1800,0)` | [预期](../reports/chapter8-testplan/cases/default-EX00.events) / [实际](../reports/chapter8-testplan/rtl-results/default-EX00-s1.json) |
| default-EX01 | 非页异常 cause=1 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 55–55 | 55–55 | `(2000,1,2000,1800,0)` | [预期](../reports/chapter8-testplan/cases/default-EX01.events) / [实际](../reports/chapter8-testplan/rtl-results/default-EX01-s1.json) |
| default-EX02 | 非页异常 cause=2 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(1c,2,ffffffff,1800,0)` | [预期](../reports/chapter8-testplan/cases/default-EX02.events) / [实际](../reports/chapter8-testplan/rtl-results/default-EX02-s1.json) |
| default-EX03 | 非页异常 cause=3 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(1c,3,1c,1800,0)` | [预期](../reports/chapter8-testplan/cases/default-EX03.events) / [实际](../reports/chapter8-testplan/rtl-results/default-EX03-s1.json) |
| default-EX04 | 非页异常 cause=4 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(1c,4,2,1800,0)` | [预期](../reports/chapter8-testplan/cases/default-EX04.events) / [实际](../reports/chapter8-testplan/rtl-results/default-EX04-s1.json) |
| default-EX05 | 非页异常 cause=5 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 52–52 | 52–52 | `(24,5,2000,1800,0)` | [预期](../reports/chapter8-testplan/cases/default-EX05.events) / [实际](../reports/chapter8-testplan/rtl-results/default-EX05-s1.json) |
| default-EX06 | 非页异常 cause=6 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(1c,6,2,1800,0)` | [预期](../reports/chapter8-testplan/cases/default-EX06.events) / [实际](../reports/chapter8-testplan/rtl-results/default-EX06-s1.json) |
| default-EX07 | 非页异常 cause=7 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 52–52 | 52–52 | `(24,7,2000,1800,0)` | [预期](../reports/chapter8-testplan/cases/default-EX07.events) / [实际](../reports/chapter8-testplan/rtl-results/default-EX07-s1.json) |
| default-EX08 | 非页异常 cause=8 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 66–66 | 66–66 | `(38,8,0,0,0)` | [预期](../reports/chapter8-testplan/cases/default-EX08.events) / [实际](../reports/chapter8-testplan/rtl-results/default-EX08-s1.json) |
| default-EX09 | 非页异常 cause=9 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 66–66 | 66–66 | `(38,9,0,800,0)` | [预期](../reports/chapter8-testplan/cases/default-EX09.events) / [实际](../reports/chapter8-testplan/rtl-results/default-EX09-s1.json) |
| default-EX11 | 非页异常 cause=11 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(1c,b,0,1800,0)` | [预期](../reports/chapter8-testplan/cases/default-EX11.events) / [实际](../reports/chapter8-testplan/rtl-results/default-EX11-s1.json) |
| default-PR01 | 老指令完成、故障rd保留、年轻跳转与存储取消；表8.3；WALLY-trap-01 | 8→相同 / 0 | 51–51 | 51–51 | `(20,2,77702373,1800,0)` | [预期](../reports/chapter8-testplan/cases/default-PR01.events) / [实际](../reports/chapter8-testplan/rtl-results/default-PR01-s1.json) |
| default-PR02 | 取消错误路径异常及未采取分支的未对齐目标；表8.8；WALLY-trap-01 | 2→相同 / 0 | 19–19 | 19–19 | `无` | [预期](../reports/chapter8-testplan/cases/default-PR02.events) / [实际](../reports/chapter8-testplan/rtl-results/default-PR02-s1.json) |
| default-CS01 | 六种CSR读改写、rd/rs1抑制与依赖；表8.6；WALLY-trap-01 | 13→相同 / 0 | 66–66 | 66–66 | `无` | [预期](../reports/chapter8-testplan/cases/default-CS01.events) / [实际](../reports/chapter8-testplan/rtl-results/default-CS01-s1.json) |
| default-CS02 | 身份寄存器及固定RV32 I/U/S misa；表8.6；WALLY-minfo-01/WALLY-misa-01 | 8→相同 / 0 | 40–40 | 40–40 | `无` | [预期](../reports/chapter8-testplan/cases/default-CS02.events) / [实际](../reports/chapter8-testplan/rtl-results/default-CS02-s1.json) |
| default-CS03 | WARL掩码、EPC/TVEC对齐及Bare SATP；表8.6/8.7；WALLY-mtvec-01/WALLY-mie-01/WALLY-satp-invalid-01 | 12→相同 / 0 | 118–118 | 118–118 | `无` | [预期](../reports/chapter8-testplan/cases/default-CS03.events) / [实际](../reports/chapter8-testplan/rtl-results/default-CS03-s1.json) |
| default-CS04 | 权限/只读/特权指令非法：mode=3 word=0xf1101373；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 47–47 | 47–47 | `(18,2,f1101373,1800,0)` | [预期](../reports/chapter8-testplan/cases/default-CS04.events) / [实际](../reports/chapter8-testplan/rtl-results/default-CS04-s1.json) |
| default-CS05 | 权限/只读/特权指令非法：mode=0 word=0x34002373；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 63–63 | 63–63 | `(34,2,34002373,0,0)` | [预期](../reports/chapter8-testplan/cases/default-CS05.events) / [实际](../reports/chapter8-testplan/rtl-results/default-CS05-s1.json) |
| default-PV01 | 权限/只读/特权指令非法：mode=0 word=0x30200073；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 63–63 | 63–63 | `(34,2,30200073,0,0)` | [预期](../reports/chapter8-testplan/cases/default-PV01.events) / [实际](../reports/chapter8-testplan/rtl-results/default-PV01-s1.json) |
| default-PV02 | 权限/只读/特权指令非法：mode=0 word=0x10200073；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 63–63 | 63–63 | `(34,2,10200073,0,0)` | [预期](../reports/chapter8-testplan/cases/default-PV02.events) / [实际](../reports/chapter8-testplan/rtl-results/default-PV02-s1.json) |
| default-PV03 | 权限/只读/特权指令非法：mode=3 word=0x12000073；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 47–47 | 47–47 | `(18,2,12000073,1800,0)` | [预期](../reports/chapter8-testplan/cases/default-PV03.events) / [实际](../reports/chapter8-testplan/rtl-results/default-PV03-s1.json) |
| default-CS06 | 权限/只读/特权指令非法：mode=1 word=0x34002373；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 63–63 | 63–63 | `(34,2,34002373,800,0)` | [预期](../reports/chapter8-testplan/cases/default-CS06.events) / [实际](../reports/chapter8-testplan/rtl-results/default-CS06-s1.json) |
| default-PV04 | 权限/只读/特权指令非法：mode=1 word=0x10200073；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 63–63 | 63–63 | `(34,2,10200073,400800,0)` | [预期](../reports/chapter8-testplan/cases/default-PV04.events) / [实际](../reports/chapter8-testplan/rtl-results/default-PV04-s1.json) |
| default-PV05 | 权限/只读/特权指令非法：mode=1 word=0x30200073；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 63–63 | 63–63 | `(34,2,30200073,800,0)` | [预期](../reports/chapter8-testplan/cases/default-PV05.events) / [实际](../reports/chapter8-testplan/rtl-results/default-PV05-s1.json) |
| default-CT01 | 64位计数器写入/高低半/禁止增长/固定零HPM槽；表8.6；§8.3新增（教材Counters未测） | 13→相同 / 0 | 103–103 | 103–103 | `无` | [预期](../reports/chapter8-testplan/cases/default-CT01.events) / [实际](../reports/chapter8-testplan/rtl-results/default-CT01-s1.json) |
| default-CTD0 | mode=0 counteren许可=false；表8.6；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01 | 7→相同 / 0 | 87–87 | 87–87 | `(64,2,c0002373,0,0)` | [预期](../reports/chapter8-testplan/cases/default-CTD0.events) / [实际](../reports/chapter8-testplan/rtl-results/default-CTD0-s1.json) |
| default-CTA0 | mode=0 counteren许可=true；表8.6；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01 | 2→相同 / 0 | 65–65 | 65–65 | `无` | [预期](../reports/chapter8-testplan/cases/default-CTA0.events) / [实际](../reports/chapter8-testplan/rtl-results/default-CTA0-s1.json) |
| default-CTD1 | mode=1 counteren许可=false；表8.6；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01 | 7→相同 / 0 | 87–87 | 87–87 | `(64,2,c0002373,800,0)` | [预期](../reports/chapter8-testplan/cases/default-CTD1.events) / [实际](../reports/chapter8-testplan/rtl-results/default-CTD1-s1.json) |
| default-CTA1 | mode=1 counteren许可=true；表8.6；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01 | 2→相同 / 0 | 65–65 | 65–65 | `无` | [预期](../reports/chapter8-testplan/cases/default-CTA1.events) / [实际](../reports/chapter8-testplan/rtl-results/default-CTA1-s1.json) |
| default-WF30 | WFI有界等待 mode=3 TW=false；表8.5/8.7；WALLY-wfi-01/WALLY-status-tw-01 | 2→相同 / 0 | 49–49 | 49–49 | `无` | [预期](../reports/chapter8-testplan/cases/default-WF30.events) / [实际](../reports/chapter8-testplan/rtl-results/default-WF30-s1.json) |
| default-WF31 | WFI有界等待 mode=3 TW=true；表8.5/8.7；WALLY-wfi-01/WALLY-status-tw-01 | 2→相同 / 0 | 55–55 | 55–55 | `无` | [预期](../reports/chapter8-testplan/cases/default-WF31.events) / [实际](../reports/chapter8-testplan/rtl-results/default-WF31-s1.json) |
| default-WF10 | WFI有界等待 mode=1 TW=false；表8.5/8.7；WALLY-wfi-01/WALLY-status-tw-01 | 2→相同 / 0 | 65–65 | 65–65 | `无` | [预期](../reports/chapter8-testplan/cases/default-WF10.events) / [实际](../reports/chapter8-testplan/rtl-results/default-WF10-s1.json) |
| default-WF11 | WFI有界等待 mode=1 TW=true；表8.5/8.7；WALLY-wfi-01/WALLY-status-tw-01 | 7→相同 / 0 | 87–87 | 87–87 | `(30,2,10500073,200800,0)` | [预期](../reports/chapter8-testplan/cases/default-WF11.events) / [实际](../reports/chapter8-testplan/rtl-results/default-WF11-s1.json) |
| default-WF01 | WFI有界等待 mode=0 TW=true；表8.5/8.7；WALLY-wfi-01/WALLY-status-tw-01 | 7→相同 / 0 | 87–87 | 87–87 | `(30,2,10500073,200000,0)` | [预期](../reports/chapter8-testplan/cases/default-WF01.events) / [实际](../reports/chapter8-testplan/rtl-results/default-WF01-s1.json) |
| default-WFWAKE | 局部使能唤醒WFI、全局屏蔽阻止陷阱；表8.5/8.9；WALLY-wfi-01/WALLY-status-mie-01 | 4→相同 / 0 | 33–33 | 33–34 | `无` | [预期](../reports/chapter8-testplan/cases/default-WFWAKE.events) / [实际](../reports/chapter8-testplan/rtl-results/default-WFWAKE-s1.json) |
| default-IR1MD | cause=1 M处理 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 70–70 | 70–70 | `(38,80000001,0,1880,2)` | [预期](../reports/chapter8-testplan/cases/default-IR1MD.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IR1MD-s1.json) |
| default-IR1MV | cause=1 M处理 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 71–71 | 71–71 | `(3c,80000001,0,1880,2)` | [预期](../reports/chapter8-testplan/cases/default-IR1MV.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IR1MV-s1.json) |
| default-IR1SD | cause=1 S委托 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 92–92 | 92–92 | `(54,80000001,0,120,2)` | [预期](../reports/chapter8-testplan/cases/default-IR1SD.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IR1SD-s1.json) |
| default-IR1SV | cause=1 S委托 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 93–93 | 93–93 | `(58,80000001,0,120,2)` | [预期](../reports/chapter8-testplan/cases/default-IR1SV.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IR1SV-s1.json) |
| default-IR3MD | cause=3 M处理 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 70–70 | 70–71 | `(34,80000003,0,1880,8)` | [预期](../reports/chapter8-testplan/cases/default-IR3MD.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IR3MD-s1.json) |
| default-IR3MV | cause=3 M处理 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 71–71 | 71–72 | `(38,80000003,0,1880,8)` | [预期](../reports/chapter8-testplan/cases/default-IR3MV.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IR3MV-s1.json) |
| default-IR5MD | cause=5 M处理 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 70–70 | 70–70 | `(38,80000005,0,1880,20)` | [预期](../reports/chapter8-testplan/cases/default-IR5MD.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IR5MD-s1.json) |
| default-IR5MV | cause=5 M处理 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 71–71 | 71–71 | `(3c,80000005,0,1880,20)` | [预期](../reports/chapter8-testplan/cases/default-IR5MV.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IR5MV-s1.json) |
| default-IR5SD | cause=5 S委托 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 92–92 | 92–92 | `(54,80000005,0,120,20)` | [预期](../reports/chapter8-testplan/cases/default-IR5SD.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IR5SD-s1.json) |
| default-IR5SV | cause=5 S委托 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 93–93 | 93–93 | `(58,80000005,0,120,20)` | [预期](../reports/chapter8-testplan/cases/default-IR5SV.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IR5SV-s1.json) |
| default-IR7MD | cause=7 M处理 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 70–70 | 70–71 | `(34,80000007,0,1880,80)` | [预期](../reports/chapter8-testplan/cases/default-IR7MD.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IR7MD-s1.json) |
| default-IR7MV | cause=7 M处理 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 71–71 | 71–72 | `(38,80000007,0,1880,80)` | [预期](../reports/chapter8-testplan/cases/default-IR7MV.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IR7MV-s1.json) |
| default-IR9MD | cause=9 M处理 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 70–70 | 70–71 | `(34,80000009,0,1880,200)` | [预期](../reports/chapter8-testplan/cases/default-IR9MD.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IR9MD-s1.json) |
| default-IR9MV | cause=9 M处理 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 71–71 | 71–72 | `(38,80000009,0,1880,200)` | [预期](../reports/chapter8-testplan/cases/default-IR9MV.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IR9MV-s1.json) |
| default-IR9SD | cause=9 S委托 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 86–86 | 86–87 | `(50,80000009,0,120,200)` | [预期](../reports/chapter8-testplan/cases/default-IR9SD.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IR9SD-s1.json) |
| default-IR9SV | cause=9 S委托 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 87–87 | 87–88 | `(54,80000009,0,120,200)` | [预期](../reports/chapter8-testplan/cases/default-IR9SV.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IR9SV-s1.json) |
| default-IR11MD | cause=11 M处理 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 70–70 | 70–71 | `(34,8000000b,0,1880,800)` | [预期](../reports/chapter8-testplan/cases/default-IR11MD.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IR11MD-s1.json) |
| default-IR11MV | cause=11 M处理 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 71–71 | 71–72 | `(38,8000000b,0,1880,800)` | [预期](../reports/chapter8-testplan/cases/default-IR11MV.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IR11MV-s1.json) |
| default-IRREM | pending保持、返回重入、第二次清除；表8.9；WALLY-trap-s-01 | 12→相同 / 0 | 97–97 | 97–97 | `(40,80000001,0,1880,2); (44,80000001,0,1880,2)` | [预期](../reports/chapter8-testplan/cases/default-IRREM.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IRREM-s1.json) |
| default-IRRES | pending保持、返回重入、第二次清除；表8.9；WALLY-trap-s-01 | 12→相同 / 0 | 113–113 | 113–113 | `(5c,80000001,0,120,2); (60,80000001,0,120,2)` | [预期](../reports/chapter8-testplan/cases/default-IRRES.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IRRES-s1.json) |
| default-IRVIEW | M/S共享中断视图、委托在M模式屏蔽及SIP写掩码；表8.6/8.9；WALLY-sie-01/WALLY-status-sie-01 | 7→相同 / 0 | 67–67 | 67–67 | `无` | [预期](../reports/chapter8-testplan/cases/default-IRVIEW.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IRVIEW-s1.json) |
| default-IRMASK0 | 局部/全局屏蔽时pending保持且不进入陷阱；表8.9；WALLY-mie-01/WALLY-status-mie-01 | 3→相同 / 0 | 68–68 | 68–68 | `无` | [预期](../reports/chapter8-testplan/cases/default-IRMASK0.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IRMASK0-s1.json) |
| default-IRMASK1 | 局部/全局屏蔽时pending保持且不进入陷阱；表8.9；WALLY-mie-01/WALLY-status-mie-01 | 3→相同 / 0 | 44–44 | 44–44 | `无` | [预期](../reports/chapter8-testplan/cases/default-IRMASK1.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IRMASK1-s1.json) |
| default-IRCROSSM | U模式到更高权限中断不依赖其全局使能；表8.9；WALLY-trap-u-01/WALLY-status-sie-01 | 7→相同 / 0 | 80–80 | 80–80 | `(48,80000001,0,0,2)` | [预期](../reports/chapter8-testplan/cases/default-IRCROSSM.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IRCROSSM-s1.json) |
| default-IRCROSSS | U模式到更高权限中断不依赖其全局使能；表8.9；WALLY-trap-u-01/WALLY-status-sie-01 | 7→相同 / 0 | 92–92 | 92–92 | `(54,80000001,0,0,2)` | [预期](../reports/chapter8-testplan/cases/default-IRCROSSS.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IRCROSSS-s1.json) |
| default-IRPRIALL | 同时pending的MEI > MSI > MTI > S类优先级；表8.9；WALLY-trap-01 | 9→相同 / 0 | 82–82 | 82–83 | `(40,8000000b,0,1880,aaa)` | [预期](../reports/chapter8-testplan/cases/default-IRPRIALL.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IRPRIALL-s1.json) |
| default-IRPRIMT | 同时pending的MEI > MSI > MTI > S类优先级；表8.9；WALLY-trap-01 | 9→相同 / 0 | 82–82 | 82–83 | `(40,80000003,0,1880,2aa)` | [预期](../reports/chapter8-testplan/cases/default-IRPRIMT.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IRPRIMT-s1.json) |
| default-IRPRIT | 同时pending的MEI > MSI > MTI > S类优先级；表8.9；WALLY-trap-01 | 9→相同 / 0 | 82–82 | 82–83 | `(40,80000007,0,1880,2a2)` | [预期](../reports/chapter8-testplan/cases/default-IRPRIT.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IRPRIT-s1.json) |
| default-IRPRIS9 | S类pending优先级SEI > SSI > STI；表8.9；WALLY-trap-01 | 7→相同 / 0 | 70–70 | 70–70 | `(38,80000009,0,1880,222)` | [预期](../reports/chapter8-testplan/cases/default-IRPRIS9.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IRPRIS9-s1.json) |
| default-IRPRIS1 | S类pending优先级SEI > SSI > STI；表8.9；WALLY-trap-01 | 7→相同 / 0 | 70–70 | 70–70 | `(38,80000001,0,1880,22)` | [预期](../reports/chapter8-testplan/cases/default-IRPRIS1.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IRPRIS1-s1.json) |
| default-IRPRIS5 | S类pending优先级SEI > SSI > STI；表8.9；WALLY-trap-01 | 7→相同 / 0 | 70–70 | 70–70 | `(38,80000005,0,1880,20)` | [预期](../reports/chapter8-testplan/cases/default-IRPRIS5.events) / [实际](../reports/chapter8-testplan/rtl-results/default-IRPRIS5-s1.json) |
| default-CTRET | 异常不退休、load停顿和WFI只退休一次；表8.6；§8.3新增 | 10→相同 / 0 | 111–111 | 111–111 | `(58,2,ffffffff,1800,0)` | [预期](../reports/chapter8-testplan/cases/default-CTRET.events) / [实际](../reports/chapter8-testplan/rtl-results/default-CTRET-s1.json) |
| default-CTCARRY | cycle/instret低半溢出向高半进位；表8.6；§8.3新增 | 5→相同 / 0 | 102–102 | 102–102 | `无` | [预期](../reports/chapter8-testplan/cases/default-CTCARRY.events) / [实际](../reports/chapter8-testplan/rtl-results/default-CTCARRY-s1.json) |
| default-CTTIME | 外部64位TIME的高低半读视图；表8.6；§8.3新增 | 3→相同 / 0 | 19–19 | 19–19 | `无` | [预期](../reports/chapter8-testplan/cases/default-CTTIME.events) / [实际](../reports/chapter8-testplan/rtl-results/default-CTTIME-s1.json) |
| default-EN3N | 数据端序 mode=3 MPRV=false；表8.7；WALLY-endianness-01 | 5→相同 / 0 | 28–28 | 28–28 | `无` | [预期](../reports/chapter8-testplan/cases/default-EN3N.events) / [实际](../reports/chapter8-testplan/rtl-results/default-EN3N-s1.json) |
| default-EN0N | 数据端序 mode=0 MPRV=false；表8.7；WALLY-endianness-01 | 5→相同 / 0 | 44–44 | 44–44 | `无` | [预期](../reports/chapter8-testplan/cases/default-EN0N.events) / [实际](../reports/chapter8-testplan/rtl-results/default-EN0N-s1.json) |
| default-EN0P | 数据端序 mode=0 MPRV=true；表8.7；WALLY-endianness-01 | 5→相同 / 0 | 34–34 | 34–34 | `无` | [预期](../reports/chapter8-testplan/cases/default-EN0P.events) / [实际](../reports/chapter8-testplan/rtl-results/default-EN0P-s1.json) |
| default-EN1N | 数据端序 mode=1 MPRV=false；表8.7；WALLY-endianness-01 | 5→相同 / 0 | 44–44 | 44–44 | `无` | [预期](../reports/chapter8-testplan/cases/default-EN1N.events) / [实际](../reports/chapter8-testplan/rtl-results/default-EN1N-s1.json) |
| default-EN1P | 数据端序 mode=1 MPRV=true；表8.7；WALLY-endianness-01 | 5→相同 / 0 | 34–34 | 34–34 | `无` | [预期](../reports/chapter8-testplan/cases/default-EN1P.events) / [实际](../reports/chapter8-testplan/rtl-results/default-EN1P-s1.json) |
| minimal-EX00 | 非页异常 cause=0 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(1c,0,1e,1800,0)` | [预期](../reports/chapter8-testplan/cases/minimal-EX00.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-EX00-s1.json) |
| minimal-EX01 | 非页异常 cause=1 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 55–55 | 55–55 | `(2000,1,2000,1800,0)` | [预期](../reports/chapter8-testplan/cases/minimal-EX01.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-EX01-s1.json) |
| minimal-EX02 | 非页异常 cause=2 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(1c,2,ffffffff,1800,0)` | [预期](../reports/chapter8-testplan/cases/minimal-EX02.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-EX02-s1.json) |
| minimal-EX03 | 非页异常 cause=3 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(1c,3,1c,1800,0)` | [预期](../reports/chapter8-testplan/cases/minimal-EX03.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-EX03-s1.json) |
| minimal-EX04 | 非页异常 cause=4 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(1c,4,2,1800,0)` | [预期](../reports/chapter8-testplan/cases/minimal-EX04.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-EX04-s1.json) |
| minimal-EX05 | 非页异常 cause=5 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 52–52 | 52–52 | `(24,5,2000,1800,0)` | [预期](../reports/chapter8-testplan/cases/minimal-EX05.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-EX05-s1.json) |
| minimal-EX06 | 非页异常 cause=6 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(1c,6,2,1800,0)` | [预期](../reports/chapter8-testplan/cases/minimal-EX06.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-EX06-s1.json) |
| minimal-EX07 | 非页异常 cause=7 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 52–52 | 52–52 | `(24,7,2000,1800,0)` | [预期](../reports/chapter8-testplan/cases/minimal-EX07.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-EX07-s1.json) |
| minimal-EX08 | 非页异常 cause=8 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 66–66 | 66–66 | `(38,8,0,0,0)` | [预期](../reports/chapter8-testplan/cases/minimal-EX08.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-EX08-s1.json) |
| minimal-EX11 | 非页异常 cause=11 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(1c,b,0,1800,0)` | [预期](../reports/chapter8-testplan/cases/minimal-EX11.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-EX11-s1.json) |
| minimal-PR01 | 老指令完成、故障rd保留、年轻跳转与存储取消；表8.3；WALLY-trap-01 | 8→相同 / 0 | 51–51 | 51–51 | `(20,2,77702373,1800,0)` | [预期](../reports/chapter8-testplan/cases/minimal-PR01.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-PR01-s1.json) |
| minimal-PR02 | 取消错误路径异常及未采取分支的未对齐目标；表8.8；WALLY-trap-01 | 2→相同 / 0 | 19–19 | 19–19 | `无` | [预期](../reports/chapter8-testplan/cases/minimal-PR02.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-PR02-s1.json) |
| minimal-CS01 | 六种CSR读改写、rd/rs1抑制与依赖；表8.6；WALLY-trap-01 | 13→相同 / 0 | 66–66 | 66–66 | `无` | [预期](../reports/chapter8-testplan/cases/minimal-CS01.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-CS01-s1.json) |
| minimal-CS02 | 身份寄存器及固定RV32 I/U/S misa；表8.6；WALLY-minfo-01/WALLY-misa-01 | 8→相同 / 0 | 40–40 | 40–40 | `无` | [预期](../reports/chapter8-testplan/cases/minimal-CS02.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-CS02-s1.json) |
| minimal-CS03 | WARL掩码、EPC/TVEC对齐及Bare SATP；表8.6/8.7；WALLY-mtvec-01/WALLY-mie-01/WALLY-satp-invalid-01 | 9→相同 / 0 | 91–91 | 91–91 | `无` | [预期](../reports/chapter8-testplan/cases/minimal-CS03.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-CS03-s1.json) |
| minimal-CS04 | 权限/只读/特权指令非法：mode=3 word=0xf1101373；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 47–47 | 47–47 | `(18,2,f1101373,1800,0)` | [预期](../reports/chapter8-testplan/cases/minimal-CS04.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-CS04-s1.json) |
| minimal-CS05 | 权限/只读/特权指令非法：mode=0 word=0x34002373；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 63–63 | 63–63 | `(34,2,34002373,0,0)` | [预期](../reports/chapter8-testplan/cases/minimal-CS05.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-CS05-s1.json) |
| minimal-PV01 | 权限/只读/特权指令非法：mode=0 word=0x30200073；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 63–63 | 63–63 | `(34,2,30200073,0,0)` | [预期](../reports/chapter8-testplan/cases/minimal-PV01.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-PV01-s1.json) |
| minimal-PV02 | 权限/只读/特权指令非法：mode=0 word=0x10200073；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 63–63 | 63–63 | `(34,2,10200073,0,0)` | [预期](../reports/chapter8-testplan/cases/minimal-PV02.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-PV02-s1.json) |
| minimal-PV03 | 权限/只读/特权指令非法：mode=3 word=0x12000073；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 47–47 | 47–47 | `(18,2,12000073,1800,0)` | [预期](../reports/chapter8-testplan/cases/minimal-PV03.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-PV03-s1.json) |
| minimal-CS07 | 权限/只读/特权指令非法：mode=3 word=0x10002373；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 47–47 | 47–47 | `(18,2,10002373,1800,0)` | [预期](../reports/chapter8-testplan/cases/minimal-CS07.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-CS07-s1.json) |
| minimal-CS08 | 权限/只读/特权指令非法：mode=3 word=0x30202373；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 47–47 | 47–47 | `(18,2,30202373,1800,0)` | [预期](../reports/chapter8-testplan/cases/minimal-CS08.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-CS08-s1.json) |
| minimal-PV06 | 权限/只读/特权指令非法：mode=3 word=0x10200073；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 47–47 | 47–47 | `(18,2,10200073,1800,0)` | [预期](../reports/chapter8-testplan/cases/minimal-PV06.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-PV06-s1.json) |
| minimal-CT01 | 64位计数器写入/高低半/禁止增长/固定零HPM槽；表8.6；§8.3新增（教材Counters未测） | 13→相同 / 0 | 103–103 | 103–103 | `无` | [预期](../reports/chapter8-testplan/cases/minimal-CT01.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-CT01-s1.json) |
| minimal-CTD0 | mode=0 counteren许可=false；表8.6；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01 | 7→相同 / 0 | 81–81 | 81–81 | `(58,2,c0002373,0,0)` | [预期](../reports/chapter8-testplan/cases/minimal-CTD0.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-CTD0-s1.json) |
| minimal-CTA0 | mode=0 counteren许可=true；表8.6；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01 | 2→相同 / 0 | 59–59 | 59–59 | `无` | [预期](../reports/chapter8-testplan/cases/minimal-CTA0.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-CTA0-s1.json) |
| minimal-WF30 | WFI有界等待 mode=3 TW=false；表8.5/8.7；WALLY-wfi-01/WALLY-status-tw-01 | 2→相同 / 0 | 49–49 | 49–49 | `无` | [预期](../reports/chapter8-testplan/cases/minimal-WF30.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-WF30-s1.json) |
| minimal-WF31 | WFI有界等待 mode=3 TW=true；表8.5/8.7；WALLY-wfi-01/WALLY-status-tw-01 | 2→相同 / 0 | 55–55 | 55–55 | `无` | [预期](../reports/chapter8-testplan/cases/minimal-WF31.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-WF31-s1.json) |
| minimal-WF01 | WFI有界等待 mode=0 TW=true；表8.5/8.7；WALLY-wfi-01/WALLY-status-tw-01 | 7→相同 / 0 | 87–87 | 87–87 | `(30,2,10500073,200000,0)` | [预期](../reports/chapter8-testplan/cases/minimal-WF01.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-WF01-s1.json) |
| minimal-WFWAKE | 局部使能唤醒WFI、全局屏蔽阻止陷阱；表8.5/8.9；WALLY-wfi-01/WALLY-status-mie-01 | 4→相同 / 0 | 33–33 | 33–34 | `无` | [预期](../reports/chapter8-testplan/cases/minimal-WFWAKE.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-WFWAKE-s1.json) |
| minimal-IR3MD | cause=3 M处理 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 70–70 | 70–71 | `(34,80000003,0,1880,8)` | [预期](../reports/chapter8-testplan/cases/minimal-IR3MD.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-IR3MD-s1.json) |
| minimal-IR3MV | cause=3 M处理 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 71–71 | 71–72 | `(38,80000003,0,1880,8)` | [预期](../reports/chapter8-testplan/cases/minimal-IR3MV.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-IR3MV-s1.json) |
| minimal-IR7MD | cause=7 M处理 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 70–70 | 70–71 | `(34,80000007,0,1880,80)` | [预期](../reports/chapter8-testplan/cases/minimal-IR7MD.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-IR7MD-s1.json) |
| minimal-IR7MV | cause=7 M处理 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 71–71 | 71–72 | `(38,80000007,0,1880,80)` | [预期](../reports/chapter8-testplan/cases/minimal-IR7MV.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-IR7MV-s1.json) |
| minimal-IR11MD | cause=11 M处理 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 70–70 | 70–71 | `(34,8000000b,0,1880,800)` | [预期](../reports/chapter8-testplan/cases/minimal-IR11MD.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-IR11MD-s1.json) |
| minimal-IR11MV | cause=11 M处理 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 71–71 | 71–72 | `(38,8000000b,0,1880,800)` | [预期](../reports/chapter8-testplan/cases/minimal-IR11MV.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-IR11MV-s1.json) |
| minimal-IRPRIALL | 同时pending的MEI > MSI > MTI > S类优先级；表8.9；WALLY-trap-01 | 9→相同 / 0 | 76–76 | 76–77 | `(34,8000000b,0,1880,888)` | [预期](../reports/chapter8-testplan/cases/minimal-IRPRIALL.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-IRPRIALL-s1.json) |
| minimal-IRPRIMT | 同时pending的MEI > MSI > MTI > S类优先级；表8.9；WALLY-trap-01 | 9→相同 / 0 | 76–76 | 76–77 | `(34,80000003,0,1880,88)` | [预期](../reports/chapter8-testplan/cases/minimal-IRPRIMT.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-IRPRIMT-s1.json) |
| minimal-IRPRIT | 同时pending的MEI > MSI > MTI > S类优先级；表8.9；WALLY-trap-01 | 9→相同 / 0 | 76–76 | 76–77 | `(34,80000007,0,1880,80)` | [预期](../reports/chapter8-testplan/cases/minimal-IRPRIT.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-IRPRIT-s1.json) |
| minimal-CTRET | 异常不退休、load停顿和WFI只退休一次；表8.6；§8.3新增 | 10→相同 / 0 | 111–111 | 111–111 | `(58,2,ffffffff,1800,0)` | [预期](../reports/chapter8-testplan/cases/minimal-CTRET.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-CTRET-s1.json) |
| minimal-CTCARRY | cycle/instret低半溢出向高半进位；表8.6；§8.3新增 | 5→相同 / 0 | 102–102 | 102–102 | `无` | [预期](../reports/chapter8-testplan/cases/minimal-CTCARRY.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-CTCARRY-s1.json) |
| minimal-CTTIME | 外部64位TIME的高低半读视图；表8.6；§8.3新增 | 3→相同 / 0 | 19–19 | 19–19 | `无` | [预期](../reports/chapter8-testplan/cases/minimal-CTTIME.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-CTTIME-s1.json) |
| minimal-EN3N | 数据端序 mode=3 MPRV=false；表8.7；WALLY-endianness-01 | 5→相同 / 0 | 28–28 | 28–28 | `无` | [预期](../reports/chapter8-testplan/cases/minimal-EN3N.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-EN3N-s1.json) |
| minimal-EN0N | 数据端序 mode=0 MPRV=false；表8.7；WALLY-endianness-01 | 5→相同 / 0 | 44–44 | 44–44 | `无` | [预期](../reports/chapter8-testplan/cases/minimal-EN0N.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-EN0N-s1.json) |
| minimal-EN0P | 数据端序 mode=0 MPRV=true；表8.7；WALLY-endianness-01 | 5→相同 / 0 | 34–34 | 34–34 | `无` | [预期](../reports/chapter8-testplan/cases/minimal-EN0P.events) / [实际](../reports/chapter8-testplan/rtl-results/minimal-EN0P-s1.json) |
| sstc-hpm-EX00 | 非页异常 cause=0 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(1c,0,1e,1800,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-EX00.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-EX00-s1.json) |
| sstc-hpm-EX01 | 非页异常 cause=1 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 55–55 | 55–55 | `(2000,1,2000,1800,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-EX01.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-EX01-s1.json) |
| sstc-hpm-EX02 | 非页异常 cause=2 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(1c,2,ffffffff,1800,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-EX02.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-EX02-s1.json) |
| sstc-hpm-EX03 | 非页异常 cause=3 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(1c,3,1c,1800,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-EX03.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-EX03-s1.json) |
| sstc-hpm-EX04 | 非页异常 cause=4 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(1c,4,2,1800,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-EX04.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-EX04-s1.json) |
| sstc-hpm-EX05 | 非页异常 cause=5 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 52–52 | 52–52 | `(24,5,2000,1800,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-EX05.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-EX05-s1.json) |
| sstc-hpm-EX06 | 非页异常 cause=6 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(1c,6,2,1800,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-EX06.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-EX06-s1.json) |
| sstc-hpm-EX07 | 非页异常 cause=7 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 52–52 | 52–52 | `(24,7,2000,1800,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-EX07.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-EX07-s1.json) |
| sstc-hpm-EX08 | 非页异常 cause=8 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 66–66 | 66–66 | `(38,8,0,0,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-EX08.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-EX08-s1.json) |
| sstc-hpm-EX09 | 非页异常 cause=9 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 66–66 | 66–66 | `(38,9,0,800,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-EX09.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-EX09-s1.json) |
| sstc-hpm-EX11 | 非页异常 cause=11 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(1c,b,0,1800,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-EX11.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-EX11-s1.json) |
| sstc-hpm-PR01 | 老指令完成、故障rd保留、年轻跳转与存储取消；表8.3；WALLY-trap-01 | 8→相同 / 0 | 51–51 | 51–51 | `(20,2,77702373,1800,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-PR01.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-PR01-s1.json) |
| sstc-hpm-PR02 | 取消错误路径异常及未采取分支的未对齐目标；表8.8；WALLY-trap-01 | 2→相同 / 0 | 19–19 | 19–19 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-PR02.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-PR02-s1.json) |
| sstc-hpm-CS01 | 六种CSR读改写、rd/rs1抑制与依赖；表8.6；WALLY-trap-01 | 13→相同 / 0 | 66–66 | 66–66 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-CS01.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-CS01-s1.json) |
| sstc-hpm-CS02 | 身份寄存器及固定RV32 I/U/S misa；表8.6；WALLY-minfo-01/WALLY-misa-01 | 8→相同 / 0 | 40–40 | 40–40 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-CS02.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-CS02-s1.json) |
| sstc-hpm-CS03 | WARL掩码、EPC/TVEC对齐及Bare SATP；表8.6/8.7；WALLY-mtvec-01/WALLY-mie-01/WALLY-satp-invalid-01 | 12→相同 / 0 | 118–118 | 118–118 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-CS03.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-CS03-s1.json) |
| sstc-hpm-CS04 | 权限/只读/特权指令非法：mode=3 word=0xf1101373；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 47–47 | 47–47 | `(18,2,f1101373,1800,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-CS04.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-CS04-s1.json) |
| sstc-hpm-CS05 | 权限/只读/特权指令非法：mode=0 word=0x34002373；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 63–63 | 63–63 | `(34,2,34002373,0,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-CS05.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-CS05-s1.json) |
| sstc-hpm-PV01 | 权限/只读/特权指令非法：mode=0 word=0x30200073；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 63–63 | 63–63 | `(34,2,30200073,0,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-PV01.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-PV01-s1.json) |
| sstc-hpm-PV02 | 权限/只读/特权指令非法：mode=0 word=0x10200073；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 63–63 | 63–63 | `(34,2,10200073,0,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-PV02.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-PV02-s1.json) |
| sstc-hpm-PV03 | 权限/只读/特权指令非法：mode=3 word=0x12000073；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 47–47 | 47–47 | `(18,2,12000073,1800,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-PV03.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-PV03-s1.json) |
| sstc-hpm-CS06 | 权限/只读/特权指令非法：mode=1 word=0x34002373；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 63–63 | 63–63 | `(34,2,34002373,800,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-CS06.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-CS06-s1.json) |
| sstc-hpm-PV04 | 权限/只读/特权指令非法：mode=1 word=0x10200073；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 63–63 | 63–63 | `(34,2,10200073,400800,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-PV04.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-PV04-s1.json) |
| sstc-hpm-PV05 | 权限/只读/特权指令非法：mode=1 word=0x30200073；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 63–63 | 63–63 | `(34,2,30200073,800,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-PV05.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-PV05-s1.json) |
| sstc-hpm-CT01 | 64位计数器写入/高低半/禁止增长/固定零HPM槽；表8.6；§8.3新增（教材Counters未测） | 13→相同 / 0 | 103–103 | 103–103 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-CT01.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-CT01-s1.json) |
| sstc-hpm-CTD0 | mode=0 counteren许可=false；表8.6；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01 | 7→相同 / 0 | 87–87 | 87–87 | `(64,2,c0002373,0,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-CTD0.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-CTD0-s1.json) |
| sstc-hpm-CTA0 | mode=0 counteren许可=true；表8.6；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01 | 2→相同 / 0 | 65–65 | 65–65 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-CTA0.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-CTA0-s1.json) |
| sstc-hpm-CTD1 | mode=1 counteren许可=false；表8.6；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01 | 7→相同 / 0 | 87–87 | 87–87 | `(64,2,c0002373,800,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-CTD1.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-CTD1-s1.json) |
| sstc-hpm-CTA1 | mode=1 counteren许可=true；表8.6；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01 | 2→相同 / 0 | 65–65 | 65–65 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-CTA1.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-CTA1-s1.json) |
| sstc-hpm-WF30 | WFI有界等待 mode=3 TW=false；表8.5/8.7；WALLY-wfi-01/WALLY-status-tw-01 | 2→相同 / 0 | 49–49 | 49–49 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-WF30.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-WF30-s1.json) |
| sstc-hpm-WF31 | WFI有界等待 mode=3 TW=true；表8.5/8.7；WALLY-wfi-01/WALLY-status-tw-01 | 2→相同 / 0 | 55–55 | 55–55 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-WF31.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-WF31-s1.json) |
| sstc-hpm-WF10 | WFI有界等待 mode=1 TW=false；表8.5/8.7；WALLY-wfi-01/WALLY-status-tw-01 | 2→相同 / 0 | 65–65 | 65–65 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-WF10.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-WF10-s1.json) |
| sstc-hpm-WF11 | WFI有界等待 mode=1 TW=true；表8.5/8.7；WALLY-wfi-01/WALLY-status-tw-01 | 7→相同 / 0 | 87–87 | 87–87 | `(30,2,10500073,200800,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-WF11.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-WF11-s1.json) |
| sstc-hpm-WF01 | WFI有界等待 mode=0 TW=true；表8.5/8.7；WALLY-wfi-01/WALLY-status-tw-01 | 7→相同 / 0 | 87–87 | 87–87 | `(30,2,10500073,200000,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-WF01.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-WF01-s1.json) |
| sstc-hpm-WFWAKE | 局部使能唤醒WFI、全局屏蔽阻止陷阱；表8.5/8.9；WALLY-wfi-01/WALLY-status-mie-01 | 4→相同 / 0 | 33–33 | 33–34 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-WFWAKE.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-WFWAKE-s1.json) |
| sstc-hpm-IR1MD | cause=1 M处理 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 70–70 | 70–70 | `(38,80000001,0,1880,2)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IR1MD.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IR1MD-s1.json) |
| sstc-hpm-IR1MV | cause=1 M处理 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 71–71 | 71–71 | `(3c,80000001,0,1880,2)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IR1MV.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IR1MV-s1.json) |
| sstc-hpm-IR1SD | cause=1 S委托 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 92–92 | 92–92 | `(54,80000001,0,120,2)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IR1SD.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IR1SD-s1.json) |
| sstc-hpm-IR1SV | cause=1 S委托 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 93–93 | 93–93 | `(58,80000001,0,120,2)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IR1SV.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IR1SV-s1.json) |
| sstc-hpm-IR3MD | cause=3 M处理 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 70–70 | 70–71 | `(34,80000003,0,1880,8)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IR3MD.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IR3MD-s1.json) |
| sstc-hpm-IR3MV | cause=3 M处理 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 71–71 | 71–72 | `(38,80000003,0,1880,8)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IR3MV.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IR3MV-s1.json) |
| sstc-hpm-IR5MD | cause=5 M处理 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 70–70 | 70–70 | `(38,80000005,0,1880,20)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IR5MD.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IR5MD-s1.json) |
| sstc-hpm-IR5MV | cause=5 M处理 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 71–71 | 71–71 | `(3c,80000005,0,1880,20)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IR5MV.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IR5MV-s1.json) |
| sstc-hpm-IR5SD | cause=5 S委托 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 92–92 | 92–92 | `(54,80000005,0,120,20)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IR5SD.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IR5SD-s1.json) |
| sstc-hpm-IR5SV | cause=5 S委托 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 93–93 | 93–93 | `(58,80000005,0,120,20)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IR5SV.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IR5SV-s1.json) |
| sstc-hpm-IR7MD | cause=7 M处理 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 70–70 | 70–71 | `(34,80000007,0,1880,80)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IR7MD.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IR7MD-s1.json) |
| sstc-hpm-IR7MV | cause=7 M处理 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 71–71 | 71–72 | `(38,80000007,0,1880,80)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IR7MV.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IR7MV-s1.json) |
| sstc-hpm-IR9MD | cause=9 M处理 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 70–70 | 70–71 | `(34,80000009,0,1880,200)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IR9MD.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IR9MD-s1.json) |
| sstc-hpm-IR9MV | cause=9 M处理 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 71–71 | 71–72 | `(38,80000009,0,1880,200)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IR9MV.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IR9MV-s1.json) |
| sstc-hpm-IR9SD | cause=9 S委托 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 86–86 | 86–87 | `(50,80000009,0,120,200)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IR9SD.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IR9SD-s1.json) |
| sstc-hpm-IR9SV | cause=9 S委托 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 87–87 | 87–88 | `(54,80000009,0,120,200)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IR9SV.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IR9SV-s1.json) |
| sstc-hpm-IR11MD | cause=11 M处理 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 70–70 | 70–71 | `(34,8000000b,0,1880,800)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IR11MD.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IR11MD-s1.json) |
| sstc-hpm-IR11MV | cause=11 M处理 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 71–71 | 71–72 | `(38,8000000b,0,1880,800)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IR11MV.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IR11MV-s1.json) |
| sstc-hpm-IRREM | pending保持、返回重入、第二次清除；表8.9；WALLY-trap-s-01 | 12→相同 / 0 | 97–97 | 97–97 | `(40,80000001,0,1880,2); (44,80000001,0,1880,2)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IRREM.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IRREM-s1.json) |
| sstc-hpm-IRRES | pending保持、返回重入、第二次清除；表8.9；WALLY-trap-s-01 | 12→相同 / 0 | 113–113 | 113–113 | `(5c,80000001,0,120,2); (60,80000001,0,120,2)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IRRES.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IRRES-s1.json) |
| sstc-hpm-IRVIEW | M/S共享中断视图、委托在M模式屏蔽及SIP写掩码；表8.6/8.9；WALLY-sie-01/WALLY-status-sie-01 | 7→相同 / 0 | 67–67 | 67–67 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IRVIEW.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IRVIEW-s1.json) |
| sstc-hpm-IRMASK0 | 局部/全局屏蔽时pending保持且不进入陷阱；表8.9；WALLY-mie-01/WALLY-status-mie-01 | 3→相同 / 0 | 68–68 | 68–68 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IRMASK0.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IRMASK0-s1.json) |
| sstc-hpm-IRMASK1 | 局部/全局屏蔽时pending保持且不进入陷阱；表8.9；WALLY-mie-01/WALLY-status-mie-01 | 3→相同 / 0 | 44–44 | 44–44 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IRMASK1.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IRMASK1-s1.json) |
| sstc-hpm-IRCROSSM | U模式到更高权限中断不依赖其全局使能；表8.9；WALLY-trap-u-01/WALLY-status-sie-01 | 7→相同 / 0 | 80–80 | 80–80 | `(48,80000001,0,0,2)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IRCROSSM.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IRCROSSM-s1.json) |
| sstc-hpm-IRCROSSS | U模式到更高权限中断不依赖其全局使能；表8.9；WALLY-trap-u-01/WALLY-status-sie-01 | 7→相同 / 0 | 92–92 | 92–92 | `(54,80000001,0,0,2)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IRCROSSS.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IRCROSSS-s1.json) |
| sstc-hpm-IRPRIALL | 同时pending的MEI > MSI > MTI > S类优先级；表8.9；WALLY-trap-01 | 9→相同 / 0 | 82–82 | 82–83 | `(40,8000000b,0,1880,aaa)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IRPRIALL.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IRPRIALL-s1.json) |
| sstc-hpm-IRPRIMT | 同时pending的MEI > MSI > MTI > S类优先级；表8.9；WALLY-trap-01 | 9→相同 / 0 | 82–82 | 82–83 | `(40,80000003,0,1880,2aa)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IRPRIMT.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IRPRIMT-s1.json) |
| sstc-hpm-IRPRIT | 同时pending的MEI > MSI > MTI > S类优先级；表8.9；WALLY-trap-01 | 9→相同 / 0 | 82–82 | 82–83 | `(40,80000007,0,1880,2a2)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IRPRIT.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IRPRIT-s1.json) |
| sstc-hpm-IRPRIS9 | S类pending优先级SEI > SSI > STI；表8.9；WALLY-trap-01 | 7→相同 / 0 | 70–70 | 70–70 | `(38,80000009,0,1880,222)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IRPRIS9.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IRPRIS9-s1.json) |
| sstc-hpm-IRPRIS1 | S类pending优先级SEI > SSI > STI；表8.9；WALLY-trap-01 | 7→相同 / 0 | 70–70 | 70–70 | `(38,80000001,0,1880,22)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IRPRIS1.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IRPRIS1-s1.json) |
| sstc-hpm-IRPRIS5 | S类pending优先级SEI > SSI > STI；表8.9；WALLY-trap-01 | 7→相同 / 0 | 70–70 | 70–70 | `(38,80000005,0,1880,20)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-IRPRIS5.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-IRPRIS5-s1.json) |
| sstc-hpm-CTRET | 异常不退休、load停顿和WFI只退休一次；表8.6；§8.3新增 | 10→相同 / 0 | 111–111 | 111–111 | `(58,2,ffffffff,1800,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-CTRET.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-CTRET-s1.json) |
| sstc-hpm-CTCARRY | cycle/instret低半溢出向高半进位；表8.6；§8.3新增 | 5→相同 / 0 | 102–102 | 102–102 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-CTCARRY.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-CTCARRY-s1.json) |
| sstc-hpm-CTTIME | 外部64位TIME的高低半读视图；表8.6；§8.3新增 | 3→相同 / 0 | 19–19 | 19–19 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-CTTIME.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-CTTIME-s1.json) |
| sstc-hpm-HPM1 | HPM事件1（退休/加载/存储/load-use/存储加载冲突）；表8.6；§8.3新增 | 5→相同 / 0 | 66–66 | 66–66 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-HPM1.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-HPM1-s1.json) |
| sstc-hpm-HPM2 | HPM事件2（退休/加载/存储/load-use/存储加载冲突）；表8.6；§8.3新增 | 5→相同 / 0 | 66–66 | 66–66 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-HPM2.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-HPM2-s1.json) |
| sstc-hpm-HPM3 | HPM事件3（退休/加载/存储/load-use/存储加载冲突）；表8.6；§8.3新增 | 5→相同 / 0 | 66–66 | 66–66 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-HPM3.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-HPM3-s1.json) |
| sstc-hpm-HPM4 | HPM事件4（退休/加载/存储/load-use/存储加载冲突）；表8.6；§8.3新增 | 5→相同 / 0 | 66–66 | 66–66 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-HPM4.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-HPM4-s1.json) |
| sstc-hpm-HPM5 | HPM事件5（退休/加载/存储/load-use/存储加载冲突）；表8.6；§8.3新增 | 5→相同 / 0 | 66–66 | 66–66 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-HPM5.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-HPM5-s1.json) |
| sstc-hpm-EN3N | 数据端序 mode=3 MPRV=false；表8.7；WALLY-endianness-01 | 5→相同 / 0 | 28–28 | 28–28 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-EN3N.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-EN3N-s1.json) |
| sstc-hpm-EN0N | 数据端序 mode=0 MPRV=false；表8.7；WALLY-endianness-01 | 5→相同 / 0 | 44–44 | 44–44 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-EN0N.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-EN0N-s1.json) |
| sstc-hpm-EN0P | 数据端序 mode=0 MPRV=true；表8.7；WALLY-endianness-01 | 5→相同 / 0 | 34–34 | 34–34 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-EN0P.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-EN0P-s1.json) |
| sstc-hpm-EN1N | 数据端序 mode=1 MPRV=false；表8.7；WALLY-endianness-01 | 5→相同 / 0 | 44–44 | 44–44 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-EN1N.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-EN1N-s1.json) |
| sstc-hpm-EN1P | 数据端序 mode=1 MPRV=true；表8.7；WALLY-endianness-01 | 5→相同 / 0 | 34–34 | 34–34 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-EN1P.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-EN1P-s1.json) |
| sstc-hpm-ST01 | 64位Sstc比较、STCE选择/写屏蔽及S访问；表8.9；WALLY-trap-01/WALLY-TEST-LIB-32.h | 8→相同 / 0 | 104–104 | 104–104 | `无` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-ST01.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-ST01-s1.json) |
| sstc-hpm-STIRQ | Sstc时间越过64位比较值、S委托陷阱并撤销pending；表8.9；WALLY-trap-s-01/WALLY-TEST-LIB-32.h | 9→相同 / 0 | 110–110 | 110–111 | `(80,80000005,0,120,20)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-STIRQ.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-STIRQ-s1.json) |
| sstc-hpm-STPER0 | Sstc访问须同时满足STCE和TM；表8.9；WALLY-csr-permission-s-01 | 6→相同 / 0 | 73–73 | 73–73 | `(48,2,14d02373,800,0)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-STPER0.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-STPER0-s1.json) |
| sstc-hpm-STPER1 | Sstc访问须同时满足STCE和TM；表8.9；WALLY-csr-permission-s-01 | 6→相同 / 0 | 73–73 | 73–73 | `(48,2,14d02373,800,20)` | [预期](../reports/chapter8-testplan/cases/sstc-hpm-STPER1.events) / [实际](../reports/chapter8-testplan/rtl-results/sstc-hpm-STPER1-s1.json) |
| reset100-EX00 | 非页异常 cause=0 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(11c,0,11e,1800,0)` | [预期](../reports/chapter8-testplan/cases/reset100-EX00.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-EX00-s1.json) |
| reset100-EX01 | 非页异常 cause=1 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 55–55 | 55–55 | `(2000,1,2000,1800,0)` | [预期](../reports/chapter8-testplan/cases/reset100-EX01.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-EX01-s1.json) |
| reset100-EX02 | 非页异常 cause=2 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(11c,2,ffffffff,1800,0)` | [预期](../reports/chapter8-testplan/cases/reset100-EX02.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-EX02-s1.json) |
| reset100-EX03 | 非页异常 cause=3 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(11c,3,11c,1800,0)` | [预期](../reports/chapter8-testplan/cases/reset100-EX03.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-EX03-s1.json) |
| reset100-EX04 | 非页异常 cause=4 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(11c,4,2,1800,0)` | [预期](../reports/chapter8-testplan/cases/reset100-EX04.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-EX04-s1.json) |
| reset100-EX05 | 非页异常 cause=5 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 52–52 | 52–52 | `(124,5,2000,1800,0)` | [预期](../reports/chapter8-testplan/cases/reset100-EX05.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-EX05-s1.json) |
| reset100-EX06 | 非页异常 cause=6 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(11c,6,2,1800,0)` | [预期](../reports/chapter8-testplan/cases/reset100-EX06.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-EX06-s1.json) |
| reset100-EX07 | 非页异常 cause=7 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 52–52 | 52–52 | `(124,7,2000,1800,0)` | [预期](../reports/chapter8-testplan/cases/reset100-EX07.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-EX07-s1.json) |
| reset100-EX08 | 非页异常 cause=8 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 66–66 | 66–66 | `(138,8,0,0,0)` | [预期](../reports/chapter8-testplan/cases/reset100-EX08.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-EX08-s1.json) |
| reset100-EX09 | 非页异常 cause=9 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 66–66 | 66–66 | `(138,9,0,800,0)` | [预期](../reports/chapter8-testplan/cases/reset100-EX09.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-EX09-s1.json) |
| reset100-EX11 | 非页异常 cause=11 与精确副作用；表8.8；WALLY-trap-01/WALLY-trap-u-01/WALLY-trap-s-01 | 8→相同 / 0 | 50–50 | 50–50 | `(11c,b,0,1800,0)` | [预期](../reports/chapter8-testplan/cases/reset100-EX11.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-EX11-s1.json) |
| reset100-PR01 | 老指令完成、故障rd保留、年轻跳转与存储取消；表8.3；WALLY-trap-01 | 8→相同 / 0 | 51–51 | 51–51 | `(120,2,77702373,1800,0)` | [预期](../reports/chapter8-testplan/cases/reset100-PR01.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-PR01-s1.json) |
| reset100-PR02 | 取消错误路径异常及未采取分支的未对齐目标；表8.8；WALLY-trap-01 | 2→相同 / 0 | 19–19 | 19–19 | `无` | [预期](../reports/chapter8-testplan/cases/reset100-PR02.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-PR02-s1.json) |
| reset100-CS01 | 六种CSR读改写、rd/rs1抑制与依赖；表8.6；WALLY-trap-01 | 13→相同 / 0 | 66–66 | 66–66 | `无` | [预期](../reports/chapter8-testplan/cases/reset100-CS01.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-CS01-s1.json) |
| reset100-CS02 | 身份寄存器及固定RV32 I/U/S misa；表8.6；WALLY-minfo-01/WALLY-misa-01 | 8→相同 / 0 | 40–40 | 40–40 | `无` | [预期](../reports/chapter8-testplan/cases/reset100-CS02.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-CS02-s1.json) |
| reset100-CS03 | WARL掩码、EPC/TVEC对齐及Bare SATP；表8.6/8.7；WALLY-mtvec-01/WALLY-mie-01/WALLY-satp-invalid-01 | 12→相同 / 0 | 118–118 | 118–118 | `无` | [预期](../reports/chapter8-testplan/cases/reset100-CS03.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-CS03-s1.json) |
| reset100-CS04 | 权限/只读/特权指令非法：mode=3 word=0xf1101373；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 47–47 | 47–47 | `(118,2,f1101373,1800,0)` | [预期](../reports/chapter8-testplan/cases/reset100-CS04.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-CS04-s1.json) |
| reset100-CS05 | 权限/只读/特权指令非法：mode=0 word=0x34002373；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 63–63 | 63–63 | `(134,2,34002373,0,0)` | [预期](../reports/chapter8-testplan/cases/reset100-CS05.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-CS05-s1.json) |
| reset100-PV01 | 权限/只读/特权指令非法：mode=0 word=0x30200073；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 63–63 | 63–63 | `(134,2,30200073,0,0)` | [预期](../reports/chapter8-testplan/cases/reset100-PV01.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-PV01-s1.json) |
| reset100-PV02 | 权限/只读/特权指令非法：mode=0 word=0x10200073；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 63–63 | 63–63 | `(134,2,10200073,0,0)` | [预期](../reports/chapter8-testplan/cases/reset100-PV02.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-PV02-s1.json) |
| reset100-PV03 | 权限/只读/特权指令非法：mode=3 word=0x12000073；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 47–47 | 47–47 | `(118,2,12000073,1800,0)` | [预期](../reports/chapter8-testplan/cases/reset100-PV03.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-PV03-s1.json) |
| reset100-CS06 | 权限/只读/特权指令非法：mode=1 word=0x34002373；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 63–63 | 63–63 | `(134,2,34002373,800,0)` | [预期](../reports/chapter8-testplan/cases/reset100-CS06.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-CS06-s1.json) |
| reset100-PV04 | 权限/只读/特权指令非法：mode=1 word=0x10200073；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 63–63 | 63–63 | `(134,2,10200073,400800,0)` | [预期](../reports/chapter8-testplan/cases/reset100-PV04.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-PV04-s1.json) |
| reset100-PV05 | 权限/只读/特权指令非法：mode=1 word=0x30200073；表8.5/8.6/8.7；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01/WALLY-trap-sret-01 | 7→相同 / 0 | 63–63 | 63–63 | `(134,2,30200073,800,0)` | [预期](../reports/chapter8-testplan/cases/reset100-PV05.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-PV05-s1.json) |
| reset100-CT01 | 64位计数器写入/高低半/禁止增长/固定零HPM槽；表8.6；§8.3新增（教材Counters未测） | 13→相同 / 0 | 103–103 | 103–103 | `无` | [预期](../reports/chapter8-testplan/cases/reset100-CT01.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-CT01-s1.json) |
| reset100-CTD0 | mode=0 counteren许可=false；表8.6；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01 | 7→相同 / 0 | 87–87 | 87–87 | `(164,2,c0002373,0,0)` | [预期](../reports/chapter8-testplan/cases/reset100-CTD0.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-CTD0-s1.json) |
| reset100-CTA0 | mode=0 counteren许可=true；表8.6；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01 | 2→相同 / 0 | 65–65 | 65–65 | `无` | [预期](../reports/chapter8-testplan/cases/reset100-CTA0.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-CTA0-s1.json) |
| reset100-CTD1 | mode=1 counteren许可=false；表8.6；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01 | 7→相同 / 0 | 87–87 | 87–87 | `(164,2,c0002373,800,0)` | [预期](../reports/chapter8-testplan/cases/reset100-CTD1.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-CTD1-s1.json) |
| reset100-CTA1 | mode=1 counteren许可=true；表8.6；WALLY-csr-permission-u-01/WALLY-csr-permission-s-01 | 2→相同 / 0 | 65–65 | 65–65 | `无` | [预期](../reports/chapter8-testplan/cases/reset100-CTA1.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-CTA1-s1.json) |
| reset100-WF30 | WFI有界等待 mode=3 TW=false；表8.5/8.7；WALLY-wfi-01/WALLY-status-tw-01 | 2→相同 / 0 | 49–49 | 49–49 | `无` | [预期](../reports/chapter8-testplan/cases/reset100-WF30.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-WF30-s1.json) |
| reset100-WF31 | WFI有界等待 mode=3 TW=true；表8.5/8.7；WALLY-wfi-01/WALLY-status-tw-01 | 2→相同 / 0 | 55–55 | 55–55 | `无` | [预期](../reports/chapter8-testplan/cases/reset100-WF31.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-WF31-s1.json) |
| reset100-WF10 | WFI有界等待 mode=1 TW=false；表8.5/8.7；WALLY-wfi-01/WALLY-status-tw-01 | 2→相同 / 0 | 65–65 | 65–65 | `无` | [预期](../reports/chapter8-testplan/cases/reset100-WF10.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-WF10-s1.json) |
| reset100-WF11 | WFI有界等待 mode=1 TW=true；表8.5/8.7；WALLY-wfi-01/WALLY-status-tw-01 | 7→相同 / 0 | 87–87 | 87–87 | `(130,2,10500073,200800,0)` | [预期](../reports/chapter8-testplan/cases/reset100-WF11.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-WF11-s1.json) |
| reset100-WF01 | WFI有界等待 mode=0 TW=true；表8.5/8.7；WALLY-wfi-01/WALLY-status-tw-01 | 7→相同 / 0 | 87–87 | 87–87 | `(130,2,10500073,200000,0)` | [预期](../reports/chapter8-testplan/cases/reset100-WF01.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-WF01-s1.json) |
| reset100-WFWAKE | 局部使能唤醒WFI、全局屏蔽阻止陷阱；表8.5/8.9；WALLY-wfi-01/WALLY-status-mie-01 | 4→相同 / 0 | 33–33 | 33–34 | `无` | [预期](../reports/chapter8-testplan/cases/reset100-WFWAKE.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-WFWAKE-s1.json) |
| reset100-IR1MD | cause=1 M处理 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 70–70 | 70–70 | `(138,80000001,0,1880,2)` | [预期](../reports/chapter8-testplan/cases/reset100-IR1MD.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IR1MD-s1.json) |
| reset100-IR1MV | cause=1 M处理 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 71–71 | 71–71 | `(13c,80000001,0,1880,2)` | [预期](../reports/chapter8-testplan/cases/reset100-IR1MV.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IR1MV-s1.json) |
| reset100-IR1SD | cause=1 S委托 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 92–92 | 92–92 | `(154,80000001,0,120,2)` | [预期](../reports/chapter8-testplan/cases/reset100-IR1SD.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IR1SD-s1.json) |
| reset100-IR1SV | cause=1 S委托 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 93–93 | 93–93 | `(158,80000001,0,120,2)` | [预期](../reports/chapter8-testplan/cases/reset100-IR1SV.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IR1SV-s1.json) |
| reset100-IR3MD | cause=3 M处理 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 70–70 | 70–71 | `(134,80000003,0,1880,8)` | [预期](../reports/chapter8-testplan/cases/reset100-IR3MD.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IR3MD-s1.json) |
| reset100-IR3MV | cause=3 M处理 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 71–71 | 71–72 | `(138,80000003,0,1880,8)` | [预期](../reports/chapter8-testplan/cases/reset100-IR3MV.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IR3MV-s1.json) |
| reset100-IR5MD | cause=5 M处理 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 70–70 | 70–70 | `(138,80000005,0,1880,20)` | [预期](../reports/chapter8-testplan/cases/reset100-IR5MD.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IR5MD-s1.json) |
| reset100-IR5MV | cause=5 M处理 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 71–71 | 71–71 | `(13c,80000005,0,1880,20)` | [预期](../reports/chapter8-testplan/cases/reset100-IR5MV.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IR5MV-s1.json) |
| reset100-IR5SD | cause=5 S委托 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 92–92 | 92–92 | `(154,80000005,0,120,20)` | [预期](../reports/chapter8-testplan/cases/reset100-IR5SD.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IR5SD-s1.json) |
| reset100-IR5SV | cause=5 S委托 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 7→相同 / 0 | 93–93 | 93–93 | `(158,80000005,0,120,20)` | [预期](../reports/chapter8-testplan/cases/reset100-IR5SV.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IR5SV-s1.json) |
| reset100-IR7MD | cause=7 M处理 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 70–70 | 70–71 | `(134,80000007,0,1880,80)` | [预期](../reports/chapter8-testplan/cases/reset100-IR7MD.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IR7MD-s1.json) |
| reset100-IR7MV | cause=7 M处理 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 71–71 | 71–72 | `(138,80000007,0,1880,80)` | [预期](../reports/chapter8-testplan/cases/reset100-IR7MV.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IR7MV-s1.json) |
| reset100-IR9MD | cause=9 M处理 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 70–70 | 70–71 | `(134,80000009,0,1880,200)` | [预期](../reports/chapter8-testplan/cases/reset100-IR9MD.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IR9MD-s1.json) |
| reset100-IR9MV | cause=9 M处理 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 71–71 | 71–72 | `(138,80000009,0,1880,200)` | [预期](../reports/chapter8-testplan/cases/reset100-IR9MV.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IR9MV-s1.json) |
| reset100-IR9SD | cause=9 S委托 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 86–86 | 86–87 | `(150,80000009,0,120,200)` | [预期](../reports/chapter8-testplan/cases/reset100-IR9SD.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IR9SD-s1.json) |
| reset100-IR9SV | cause=9 S委托 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 87–87 | 87–88 | `(154,80000009,0,120,200)` | [预期](../reports/chapter8-testplan/cases/reset100-IR9SV.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IR9SV-s1.json) |
| reset100-IR11MD | cause=11 M处理 直接；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 70–70 | 70–71 | `(134,8000000b,0,1880,800)` | [预期](../reports/chapter8-testplan/cases/reset100-IR11MD.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IR11MD-s1.json) |
| reset100-IR11MV | cause=11 M处理 向量；表8.9；WALLY-trap-01/WALLY-trap-s-01/WALLY-mtvec-01/WALLY-stvec-01 | 9→相同 / 0 | 71–71 | 71–72 | `(138,8000000b,0,1880,800)` | [预期](../reports/chapter8-testplan/cases/reset100-IR11MV.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IR11MV-s1.json) |
| reset100-IRREM | pending保持、返回重入、第二次清除；表8.9；WALLY-trap-s-01 | 12→相同 / 0 | 97–97 | 97–97 | `(140,80000001,0,1880,2); (144,80000001,0,1880,2)` | [预期](../reports/chapter8-testplan/cases/reset100-IRREM.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IRREM-s1.json) |
| reset100-IRRES | pending保持、返回重入、第二次清除；表8.9；WALLY-trap-s-01 | 12→相同 / 0 | 113–113 | 113–113 | `(15c,80000001,0,120,2); (160,80000001,0,120,2)` | [预期](../reports/chapter8-testplan/cases/reset100-IRRES.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IRRES-s1.json) |
| reset100-IRVIEW | M/S共享中断视图、委托在M模式屏蔽及SIP写掩码；表8.6/8.9；WALLY-sie-01/WALLY-status-sie-01 | 7→相同 / 0 | 67–67 | 67–67 | `无` | [预期](../reports/chapter8-testplan/cases/reset100-IRVIEW.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IRVIEW-s1.json) |
| reset100-IRMASK0 | 局部/全局屏蔽时pending保持且不进入陷阱；表8.9；WALLY-mie-01/WALLY-status-mie-01 | 3→相同 / 0 | 68–68 | 68–68 | `无` | [预期](../reports/chapter8-testplan/cases/reset100-IRMASK0.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IRMASK0-s1.json) |
| reset100-IRMASK1 | 局部/全局屏蔽时pending保持且不进入陷阱；表8.9；WALLY-mie-01/WALLY-status-mie-01 | 3→相同 / 0 | 44–44 | 44–44 | `无` | [预期](../reports/chapter8-testplan/cases/reset100-IRMASK1.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IRMASK1-s1.json) |
| reset100-IRCROSSM | U模式到更高权限中断不依赖其全局使能；表8.9；WALLY-trap-u-01/WALLY-status-sie-01 | 7→相同 / 0 | 80–80 | 80–80 | `(148,80000001,0,0,2)` | [预期](../reports/chapter8-testplan/cases/reset100-IRCROSSM.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IRCROSSM-s1.json) |
| reset100-IRCROSSS | U模式到更高权限中断不依赖其全局使能；表8.9；WALLY-trap-u-01/WALLY-status-sie-01 | 7→相同 / 0 | 92–92 | 92–92 | `(154,80000001,0,0,2)` | [预期](../reports/chapter8-testplan/cases/reset100-IRCROSSS.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IRCROSSS-s1.json) |
| reset100-IRPRIALL | 同时pending的MEI > MSI > MTI > S类优先级；表8.9；WALLY-trap-01 | 9→相同 / 0 | 82–82 | 82–83 | `(140,8000000b,0,1880,aaa)` | [预期](../reports/chapter8-testplan/cases/reset100-IRPRIALL.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IRPRIALL-s1.json) |
| reset100-IRPRIMT | 同时pending的MEI > MSI > MTI > S类优先级；表8.9；WALLY-trap-01 | 9→相同 / 0 | 82–82 | 82–83 | `(140,80000003,0,1880,2aa)` | [预期](../reports/chapter8-testplan/cases/reset100-IRPRIMT.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IRPRIMT-s1.json) |
| reset100-IRPRIT | 同时pending的MEI > MSI > MTI > S类优先级；表8.9；WALLY-trap-01 | 9→相同 / 0 | 82–82 | 82–83 | `(140,80000007,0,1880,2a2)` | [预期](../reports/chapter8-testplan/cases/reset100-IRPRIT.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IRPRIT-s1.json) |
| reset100-IRPRIS9 | S类pending优先级SEI > SSI > STI；表8.9；WALLY-trap-01 | 7→相同 / 0 | 70–70 | 70–70 | `(138,80000009,0,1880,222)` | [预期](../reports/chapter8-testplan/cases/reset100-IRPRIS9.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IRPRIS9-s1.json) |
| reset100-IRPRIS1 | S类pending优先级SEI > SSI > STI；表8.9；WALLY-trap-01 | 7→相同 / 0 | 70–70 | 70–70 | `(138,80000001,0,1880,22)` | [预期](../reports/chapter8-testplan/cases/reset100-IRPRIS1.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IRPRIS1-s1.json) |
| reset100-IRPRIS5 | S类pending优先级SEI > SSI > STI；表8.9；WALLY-trap-01 | 7→相同 / 0 | 70–70 | 70–70 | `(138,80000005,0,1880,20)` | [预期](../reports/chapter8-testplan/cases/reset100-IRPRIS5.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-IRPRIS5-s1.json) |
| reset100-CTRET | 异常不退休、load停顿和WFI只退休一次；表8.6；§8.3新增 | 10→相同 / 0 | 111–111 | 111–111 | `(158,2,ffffffff,1800,0)` | [预期](../reports/chapter8-testplan/cases/reset100-CTRET.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-CTRET-s1.json) |
| reset100-CTCARRY | cycle/instret低半溢出向高半进位；表8.6；§8.3新增 | 5→相同 / 0 | 102–102 | 102–102 | `无` | [预期](../reports/chapter8-testplan/cases/reset100-CTCARRY.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-CTCARRY-s1.json) |
| reset100-CTTIME | 外部64位TIME的高低半读视图；表8.6；§8.3新增 | 3→相同 / 0 | 19–19 | 19–19 | `无` | [预期](../reports/chapter8-testplan/cases/reset100-CTTIME.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-CTTIME-s1.json) |
| reset100-EN3N | 数据端序 mode=3 MPRV=false；表8.7；WALLY-endianness-01 | 5→相同 / 0 | 28–28 | 28–28 | `无` | [预期](../reports/chapter8-testplan/cases/reset100-EN3N.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-EN3N-s1.json) |
| reset100-EN0N | 数据端序 mode=0 MPRV=false；表8.7；WALLY-endianness-01 | 5→相同 / 0 | 44–44 | 44–44 | `无` | [预期](../reports/chapter8-testplan/cases/reset100-EN0N.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-EN0N-s1.json) |
| reset100-EN0P | 数据端序 mode=0 MPRV=true；表8.7；WALLY-endianness-01 | 5→相同 / 0 | 34–34 | 34–34 | `无` | [预期](../reports/chapter8-testplan/cases/reset100-EN0P.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-EN0P-s1.json) |
| reset100-EN1N | 数据端序 mode=1 MPRV=false；表8.7；WALLY-endianness-01 | 5→相同 / 0 | 44–44 | 44–44 | `无` | [预期](../reports/chapter8-testplan/cases/reset100-EN1N.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-EN1N-s1.json) |
| reset100-EN1P | 数据端序 mode=1 MPRV=true；表8.7；WALLY-endianness-01 | 5→相同 / 0 | 34–34 | 34–34 | `无` | [预期](../reports/chapter8-testplan/cases/reset100-EN1P.events) / [实际](../reports/chapter8-testplan/rtl-results/reset100-EN1P-s1.json) |

## 6. 既有测试复验与覆盖边界

全量Scala的JUnit记录逐条归档至`junit/`；每项按suite+name生成稳定UT哈希编号，完整清单见[unit-tests.json](../reports/chapter8-testplan/unit-tests.json)。
既有Chapter8AcceptanceSpec补充：分支/JAL/JALR三类取指未对齐、嵌套U→S→M异常委托、年轻跳转/故障取消、WFI老W提交与年轻前递、FENCE/FENCE.I、大小端字节/半字真实存储。
CSRAccessSpec检查六种操作的所有抑制规则与权限矩阵；TrapSpec检查异常两两优先级、64种中断来源子集、委托与模式；CSRStatus/PrivMode检查返回的MPRV与栈位；CSRCounters检查写入优先于增长、抑制、权限、高低半与事件编号合法化。
JUnit通过[ScalaTest官方-u报告参数](https://www.scalatest.org/user_guide/using_scalatest_with_sbt)输出到本轮独立目录。这些检查均在本轮重新执行；与共享用例重复的行为不额外增加配置实例通过率。

本轮是定向功能验证，不是指令集认证、形式证明或代码覆盖率收敛。独立RTL检查器独立实现时序/事件检查，但共用手写程序与期望，仍存在共同误解规范的风险。
所有有副作用存储都可观测，最终寄存器状态只有程序显式存入签名者可由RTL确认；不存在逐指令参考模型比对。
外部中断只验证同步输入协议，未验证异步CDC、电气时序或外设仲裁。种子改变初始状态/等待延迟，未覆盖任意时刻的所有中断交错；叶模块的穷举测试补充边界。
默认无总线反压；WFI与流水线冒险形成实际停顿，外部StallW/FlushM门控在独立Privileged/CSR模块测试中覆盖。

## 7. 首次失败、根因、处理与复验

首次证据保存在`first-evidence/`，构建错误/中断运行日志同样归档。首轮Scala发现预期错误后中断（退出130），不能计入完整回归。

| 问题 | 触发与首次现象 | 根因与处理 | 复验 |
| --- | --- | --- | --- |
| MPIE预期错误 | U/S异常日志status比预期少0x80 | Trap覆盖MPIE←原MIE（为0）；不能沿用MRET设置的MPIE=1。修正预期；DUT不变 | EX08/EX09、权限/非法返回/TW/计数权限系列最终两层全部通过 |
| TVEC WARL预期错误 | CS03写0x7ff实际读0x7c1 | 本平台以MODE最低位选择向量，并将BASE对齐64字节；该选择已在CSRMachineSpec独立固定字面量检查。修正夹具 | 四配置CS03通过 |
| S类优先级预期错误 | IRPRIS5期望STI、实际SSI | 官方顺序SEI > SSI > STI；修正测试组合与独立预期，保留首次RTL JSON与events | IRPRIS9/1/5和全部IRQ用例通过 |
| 关闭S的委托CSR夹具错误 | minimal-CS03没有存储并超时10000拍 | 本实现关闭S时medeleg/mideleg不存在；从正常掩码读写表剔除，另以CS08检查非法访问 | minimal-CS03/CS08通过 |
| HPM原始冒险事件夹具 | HPM4多计1次 | 原始LoadStallD也包含年轻CSR读后存储的依赖，恰与inhibit提交同拍；在停止计数与读取签名之间加入NOP隔离，预期仍由两组load-use独立推出 | HPM4最终通过 |
| Sstc初始pending预期 | STPER1预期0实际32 | stimecmp和TIME均复位0，STCE开启且比较为大于等于，故合法STIP=1；修正独立预期 | STPER1最终通过 |
| 向量slot1夹具放置错误 | 导出器org要求失败 | slot1距BASE只有4字节，双指令poison超过槽空间。仅保留一条poison store | 所有向量用例通过，访问BASE即产生额外存储并失败 |
| Scala夹具编译问题 | JSON字符串推断、嵌套块、ParallelTestExecution返回类型 | 显式ujson.Str/locally/具体newInstance类型；均限测试代码 | 最终完整compile/test通过 |

独立审阅还发现验证基础设施的观察窗口边界、新鲜性及JUnit完整性问题：均先以负向测试复现，再修复并重新运行全部证据流水线。报告器同时拒绝超限、负数和无序周期记录。

新鲜性门控曾拒绝共享旧目录中仍由先前中断JVM写入的记录，随后把完整验证的全部产物隔离到唯一运行目录；最终只归档隔离目录内的完整证据。

本轮未发现必须修复的生产核心缺陷；所有修正都限定于新增测试基础设施。原始三文件快照不混入这些修正。
最终每个RTL用例三种种子在同一实例上两次复位均通过，完整签名一致；cycles的种子差异来自等待延迟，已逐运行记录。

## 8. 检查器负向验证与报告完整性

负向测试直接运行同一生产RTL驱动，仅修改预期或周期上限：

| 故意错误 | 检查器失败原因 | 返回码 | 证据 |
| --- | --- | --- | --- |
| signature | signature mismatch at event 0 | 1 | [JSON](../reports/chapter8-testplan/negative/signature.json) / [日志](../reports/chapter8-testplan/negative/signature.log) |
| missing | missing store | 1 | [JSON](../reports/chapter8-testplan/negative/missing.json) / [日志](../reports/chapter8-testplan/negative/missing.log) |
| extra | extra store | 1 | [JSON](../reports/chapter8-testplan/negative/extra.json) / [日志](../reports/chapter8-testplan/negative/extra.log) |
| timeout | timeout | 1 | [JSON](../reports/chapter8-testplan/negative/timeout.json) / [日志](../reports/chapter8-testplan/negative/timeout.log) |
| observation-window | timeout: incomplete observation window | 1 | [JSON](../reports/chapter8-testplan/negative/observation-window.json) / [日志](../reports/chapter8-testplan/negative/observation-window.log) |

`signature`翻转首条预期数据；`missing`在期望尾部增加DUT未产生的事件；`extra`删除完成存储预期，使实际完成存储成为额外事件；`timeout`限定1拍。
首次负向验证在驱动尚未实现时失败；审阅另复现完成沿接近周期上限时漏检观察窗口，新增observation-window负向用例要求失败，修复两层驱动后复验。RED日志保留；实现后五种错误均被检测，测试框架因此通过，见[negative-green.log](../reports/chapter8-testplan/negative-green.log)。
报告入口首先新建独立runs目录（current-run.txt指向本轮），隔离镜像、签名、日志、Scala工作目录与JUnit，避免并发/中断旧任务混写；记录session.json的UTC起点和全部验证源码SHA-256，最终要求源码未变化，所有本轮结果、JUnit和日志均晚于起点；完整回归清除CHAPTER8_CASE_FILTER。报告器核对全部共享JUnit身份、29个suite清单、590个testcase与日志总数，拒绝跳过、失败或缺失记录；重新核对每次签名、复位身份、种子、完成后8拍和合法周期。
报告器自身负向单元测试检查失败、缺失/错签名、观察拍数不足，不允许由汇总把不完整记录转为通过。

## 9. 归档、校验与复现

归档入口：[results.csv](../reports/chapter8-testplan/results.csv)、[results.json](../reports/chapter8-testplan/results.json)、[metadata.json](../reports/chapter8-testplan/metadata.json)、[SHA256SUMS](../reports/chapter8-testplan/SHA256SUMS)。
所有报告引用的镜像、预期events、实际JSON、日志、源码差异与工具记录在`docs/reports/chapter8-testplan/`；SHA256SUMS覆盖归档全部文件，不包含自身。

```sh
cd docs/reports/chapter8-testplan
sha256sum -c SHA256SUMS
cd ../../..
make report-chapter8 SBT=./scripts/sbt-local.sh
```

请在仓库根目录使用本地固定版本工具链；也可指定SBT/VERILATOR。复现不需要RISC-V汇编器，指令由既有独立手编码器生成。
本地CVW和教材只用于报告的来源指纹，运行Scala和独立RTL不依赖CVW的外设、Spike或网络。
仅复验单个Scala场景可用`CHAPTER8_CASE_FILTER='default-EX02' ./scripts/sbt-local.sh 'testOnly riscvpipeline.Chapter8TestPlanSpec'`；完整报告必须使用全量证据。
