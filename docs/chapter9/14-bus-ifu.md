# BusIFU 与 IFU 接入

IFUIO 增加 BusFlushF、BusStallF、BusCommittedF 与 AHBMasterIO。config.bus 为 Some 时
IFU 委托 BusIFU；默认 None 分支保持原 IROM 行为，新增总线端口输出空闲常量。
PipelineBase 暂将新增输入接安全常量，待整机阶段连接 EBU 与全局停顿。

BusIFU 支持可选同步 IROM 与 BootROM/RAM 总线取指。IROM 容量为 imemDepth 个字，
基址沿用 resetVector 按容量对齐的约定。APB 和未映射地址不发请求，生成 D 级取指
访问故障；非 4 字节对齐 PC 同样不发无效请求。总线只读 NONSEQ、HSIZE=2，
HBURST=0、HPROT=2、HMASTLOCK=0，写数据与掩码为零。

BusRW 仅由 PC 地址属性决定，不用 StallF/D 反向门控。BusFSM Stall 使用 StallD，
Flush 使用 BusFlushF 与实际 RedirectM/PCSrcE/FlushD。响应先进入只读 FetchBuffer，
Mem3 等到 D 可接收时，指令与保存的 BusPC 在同一消费沿进入 D，PCF 才正常推进。
只读 Mem3 的 committed 为零，允许较老指令在此时重定向。
已接受请求遇到重定向仍完成 Data，BusKilled 标记使旧响应排空后被丢弃，不会与新 PC
错误配对。同步 IROM 结果直接旁路到 D，内部保持最后响应；本地正常路径不增加一拍。
所有新状态采用 Bool 同步复位，组合请求和控制不额外与 reset 门控。

2026-10-17 TDD：先用输出空闲的 scaffold 运行六项测试，六项均按预期功能失败；
生产实现后 BusIFUSpec 6/6 通过：

- 前端停顿中仍发起请求，延迟 Data 完成，Mem3 跨停顿保持并将指令与 PC 一同消费。
- 同步本地 IROM 无额外延迟，指令、PCD、PCF 跨五拍独立停顿保持。
- 未接受请求可取消并重定向；已接受请求排空后丢弃旧响应，正确获取新目标指令。
- APB 和未映射地址均不发总线请求，并产生相应 D 级访问故障。

统一使用 `flock target/chapter9-sbt.lock env JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx3g'
./scripts/sbt-local.sh`，临时 Test/sources 仅选择 BusIFUSpec 与 ProjectTest，执行
`testOnly riscvpipeline.ifu.BusIFUSpec`。GenerateBusIFU 分别生成纯总线和带 IROM 的 RTL，
两者 `verilator --lint-only -Wno-fatal --top-module BusIFU` 成功且无 RTL 警告。
证据在 `target/chapter9/modules/bus-ifu/`：red/green 日志、rtl/、tim/、lint 日志与测试镜像。

范围：没有缓存、burst、atomic 或 AHB ERROR；全局停顿、精确异常和旧路径回归由
整机验收继续检查。取指复用已注明 CVW 出处与许可证的 AHBInterface/BusFSM。

整机集成修正：直接把受实际 Redirect/FlushD 影响的 BusFSM BusStall 输出接到全局
停顿，会形成 StallM→实际 Redirect→Flush→BusStall→StallM 的组合环。
BusStallF 改由已寄存 Data committed 与未接受 Adr 的原始请求、BusFlushF 产生；
请求取消仍作用于 HTRANS，实际 Redirect 不再反向改变全局等待。原始 M 级取消意图
经 BusFlushF 独立解除 Adr 等待。新增回归验证 BusFlushF=0、实际 Redirect=1 时
HTRANS=IDLE 而原始 Adr 等待保持。reset 仍只复位状态，不增加组合请求或停顿门控。

集成修正验证：BusIFUSpec 7/7 与 BusPipelineSpec 3/3 联合通过（共 10/10），证据为
`target/chapter9/modules/bus-pipeline/green.log`；纯总线、IROM 两配置重新生成并 lint
通过，无 RTL 警告。新增回归覆盖上述取消与原始等待的区别。
