# 第九章共享接口与实现约定

2026-10-17 连续实施使用此接口契约。公开类型放在 `riscvpipeline.ebu`，配置放在
`riscvpipeline.config`；参考 CVW `src/ebu`、`src/uncore`，均显式 clk/reset。
新增组合控制不与 `!reset` 门控；寄存器使用 Bool 同步复位。

## 总线类型

`AHBMasterIO`：输出 HADDR:34、HWDATA:32、HWSTRB:4、HWRITE:Bool、HSIZE:3、
HBURST:3、HPROT:4、HTRANS:2、HMASTLOCK:Bool；输入 HRDATA:32、HREADY:Bool、HRESP:Bool。
单次 NONSEQ，HBURST/HMASTLOCK=0，HPROT 指令=2、数据=3；HRESP 仅 OKAY。

`AHBSlaveIO`：输入 HSEL、HADDR:34、HWDATA:32、HWSTRB:4、HWRITE、HSIZE:3、
HTRANS:2、HREADY；输出 HRDATA:32、HREADYOUT、HRESP。与 master 同域时钟。

`APBMasterIO`：输出 PADDR:32、PWDATA:32、PSTRB:4、PWRITE、PSEL、PENABLE；
输入 PRDATA:32、PREADY。Slave 用 Flipped。无 PSLVERR。

## 配置与地址

`BusConfig` 字段：bootRomBase:BigInt=0x1000、bootRomBytes:Int=4096、
bootRomInitFile:Option[String]=None、ramBase:BigInt=0x80000000、ramBytes:Int=65536、
apbBase:BigInt=0x10000000、apbBytes:Int=4096、ramLatency:Int=0、romLatency:Int=0、
apbLatency:Int=0、iromSupported:Boolean=false、dtimSupported:Boolean=false。
大小为字节，ROM/RAM/APB 窗口对齐、为二次幂且互不重叠；等待 0..255 拍。
RAM 不预初始化；程序位于 BootROM/本地 IROM，RAM 代码通过 CPU store 写入后执行。

`CpuConfig` 末尾增加 `bus: Option[BusConfig] = None`，旧构造默认和旧顶层兼容。
纯总线复位 0x1000，TIM＋总线复位 0，后者 local IROM/DTIM 默认各 4 KiB。
数据 DTIM 在零地址窗口，IROM 基址沿用 resetVector 按容量对齐的旧约定。

## 叶模块约定

AHBInterface(readOnly:Boolean=false) 的 IO：Stall、Flush、BusRW:2、HREADY、
HRDATA:32、ByteMask:4、WriteData:32 输入；HTRANS:2、HWRITE、HWDATA:32、HWSTRB:4、
BusStall、BusCommitted、FetchBuffer:32 输出。写数据和掩码在地址接受沿保存，读在
BusFSM CaptureEn 保存；Stall 不阻止捕获，Mem3 保持且不重发。

ControllerInput 的平坦端口及 EBUFSMArb/EBU 沿用 CVW 同名接口，地址宽 34，
无 burst 计数。EBU LSU 优先，保存/重放 IFU 地址；Data 归属决定 ready，不能让另一方
提前完成。EBU 的外侧端口使用平坦 HADDR/HWDATA/.../HRDATA/HREADY/HRESP 名称。

AdrDec IO 沿用 CVW PhysicalAddress/Base/Range:34、Supported、AccessValid、Size:2、
SizeMask:4、Sel；Range 为字节容量减一。AdrDecs(config) IO 为 HADDR:34、HSIZE:3、
HSEL:4，bit0=None、bit1=BootROM、bit2=RAM、bit3=APB。

RAMAHB(base:BigInt=0x80000000,sizeBytes:Int=65536,extraLatency:Int=0)、
ROMAHB(base:BigInt=0x1000,sizeBytes:Int=4096,initFile:Option[String]=None,
extraLatency:Int=0) 使用 `val io=IO(new AHBSlaveIO)`，原生同步读，RAM 按 HWSTRB 写。
RAM 单端口写后读要插入必要等待；可变等待期间保持响应；写副作用只在完成沿一次。

AHBAPBBridge IO 为 `ahb: AHBSlaveIO` 和 `apb: APBMasterIO`。
桥保留 Idle/Setup/Access 状态以及接受后的地址和写方向；写数据相位直接连接
`PWDATA=HWDATA`、`PSTRB=HWSTRB`。AHB master 必须在 HREADY 等待期间保持
HWDATA/HWSTRB；真实 AHBInterface 的 WriteBuffer/MaskBuffer 承担这一职责。
桥的读响应为 `HRDATA = PSEL ? PRDATA : 0`，没有完成读数据保持寄存器；Idle 返回零。
读完成后的值由上游 AHBInterface 的 FetchBuffer 保存，后续写不触发读捕获。
桥精简时已通过工作树第九章97项及独立索引快照144条整机记录验证，源码边界与历史证据
见 [AHB/APB 桥精简验证报告](23-ahb-apb-simplification.md)。
APBRegisterBank(extraLatency:Int=0) 的 io.apb 为 Flipped(APBMasterIO)，
offset0 为 scratch 可按字节写，offset4 为只读写完成次数；其他寄存器读零、写忽略。
`Access = PSEL && PENABLE`，`Complete = Access && PREADY`，无 Completed 保持位。
Setup及PREADY为低的等待拍没有副作用；每个完成的写Access均计数，包括零掩码、只读
及未定义寄存器地址写。合法master完成后下一拍必须使PENABLE=0，进入Idle或Setup，
PSEL可保持有效以开始下一笔；外设不再额外防护完成后持续Access的行为。
可变等待、字译码、字节掩码及同步复位不变，本轮验证状态见
[APB 完成条件精简验证报告](24-apb-completion-simplification.md)：工作树第九章100/100、
数字状态机索引快照关联24/24已通过，RTL及整机证据分别记录。
该快照整机矩阵144/144、独立报告及源码/SHA256SUMS核验均通过；原有8份用户文件
字节完全保留，见报告24的最终证据。
Uncore(config:BusConfig) 的 io.ahb 为 Flipped(AHBMasterIO)，内部连接上述三个设备。
响应选择必须用仅在 HREADY 上升沿更新的延迟 HSEL，复位选 None 防死锁。

## 核心集成

LSU 新增 io.StallM、io.BusFlushM 输入、io.BusStallM/io.BusCommittedM 输出及 io.ahb。
IFU 新增 io.BusFlushF 输入、io.BusStallF/io.BusCommittedF 输出及 io.ahb。
PipelineBase 只实例化一个顶层 Hazard；其输入除 IEU 源/目的寄存器、有效位、
写回资格、源使用、访存控制和 `PCSrcE` 外，还包含
`IFUStallF/LSUStallM/CommittedF/CommittedM/TrapM/WFIStallM/RedirectIntentM/RedirectM`。
输出 `ForwardAE/BE`、`LoadStallD/StoreStallD`、最终 `StallF/D/M`、`FlushD/E`、
`FlushBusF/M`。无总线时四个总线状态输入绑零，WFI 等待仍有效。
IEU 接收前递选择、`FlushE` 和原 `StallM/FlushM`，不再拥有内部 Hazard。
完整组合公式见 [统一冒险控制](15-bus-hazard.md)，CPU/SoC 对外端口不变。

总线等待仍使用当前 StallM 排空 W 的语义；不能反向用总 StallM 抑制 BusRW。
IFU Mem3 committed=0，LSU Mem3 committed=1；同步响应和元数据跨其他方的停顿保持。
M 级原始 Trap/Ret/CSR/Fence 意图只取消尚未接受的请求；实际冲刷/更新/退休统一发生。
E 分支等总线完成后重定向，避免 PCSrcE 与 bus stall 的组合环。

BusPipeline(config:CpuConfig) 要求 config.bus 非空且 privilegedSupported=true，
保持原 io 六观察输出，增加 ahb:AHBMasterIO 与 interrupts:InterruptInputs。
RiscvPipelineSoC(config:CpuConfig) 组合 BusPipeline 和 Uncore，保留 io 与 interrupts。
生产观察输出记录 M 槽消费时的架构 store 完成；设备 Data 物理副作用另由总线证据核对。

## 并行工作约定

生产、测试及报告按所有权分工。sbt 统一用 `flock target/chapter9-sbt.lock` 串行执行，
每模块的日志、RTL 和结果存独立 target 子目录。Git 操作用 `flock target/chapter9-git.lock`，
只暂存/提交本模块明确路径，禁止 git add .，禁止提交其他人的进行中改动。
