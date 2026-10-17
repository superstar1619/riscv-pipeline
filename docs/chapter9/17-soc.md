# RiscvPipelineSoC 与验证观察层

RiscvPipelineSoC 组合 BusPipeline 与 Uncore，保留六个 RiscvPipelineIO 架构观察输出和
同域 InterruptInputs。显式 clk/reset 下，核心与设备采用同一时钟和 Bool 同步复位。
公开 core、uncore 实例便于测试观察，生产 IO 不增加内部调试字段。默认配置为特权
RV32、resetVector=0x1000、BusConfig 默认窗口；总线和特权支持均为必需配置。

GenerateRiscvPipelineSoC 参数依次为目标目录、镜像或 `-`、`bus|mixed`、RAM 等待与
APB 等待。生成入口的 mixed 配置使用 4 KiB 本地 IROM/DTIM、复位 0；bus 配置复位
0x1000，从 BootROM 取指。RAM 不使用程序镜像初始化。

仅在 src/test/scala 中提供 Chapter9Observer 与 GenerateChapter9Observer，后者接受
`<rtlDir> <imagePath> <bus|mixed> <ramLatency> <apbLatency>`，顶层固定 Chapter9Observer。
观察端口与独立 C++ 采集器逐字匹配：

- commit_valid/pc32/instruction32/rd5/wdata32：M→W 真正接受沿保存 PC 与指令，W 级
  实际有效、目的寄存器与结果组成退休事件；非寄存器写及 x0 的报告结果为零。
- commit_trap、trap_pc32/cause6/interrupt：仅在实际接受 trap 的沿前报告，候选在总线
  停顿中保持时不重复报告。
- store_valid/addr32/data32/mask4：生产架构 store 消费事件，数据保留 CPU 原始写值。
- event_valid/kind4/addr32/wdata32/rdata32/mask4：全局 AHB Data 真正完成事件；地址、
  写方向与 HPROT 仅在 HREADY 接受沿更新，等待期间保持。kind 0/1 为 ROM/RAM
  取指、2/3 为 RAM 读/写、4/5 为 APB 读/写、6 为 ROM 数据读、15 为未识别区域。
- bus_wait、ifu_wait、lsu_wait、committed_f/m：总线等待及双方已接受状态的逐拍证据。

观察层使用测试范围 expose 接口读取生产信号，没有 shadow RAM 或推测读值。实际
CPU 的 LW 退休值与设备物理写事件分开采集，供完整 Test Plan 的外部预期核验。

2026-10-17 TDD：先使用公开架构输出为零的 SoC scaffold，RiscvPipelineSoCSpec 四项
均因缺少预期架构 stores 而失败。完成 io 与真实 core.io 的连接后，四项全部通过：
纯总线/TIM 混合各覆盖 RAM 额外等待 0/2、APB 等待 1，每项同实例复位运行两次，
共八次程序执行。11 条指令的程序完成 RAM store/load、依赖加法、APB store/load 和
RAM 回写；检查 CPU LW 读回 42、依赖结果 43、APB 读回 43，四次架构 store 与四次
真实总线写逐项相等。完成后继续观察十拍，确认无重复写。运行中有真实总线等待。

统一通过 `flock target/chapter9-sbt.lock env JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx3g'
./scripts/sbt-local.sh`，临时 Test/sources 选择本模块两个测试文件与 ProjectTest，执行
`testOnly riscvpipeline.RiscvPipelineSoCSpec`。生产和观察层生成均成功；两份 RTL 均经
`verilator --lint-only -Wall -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL` 检查通过，无其他 RTL
警告。证据目录 `target/chapter9/modules/soc/` 包含 red.log、green.log、生成 RTL、
测试镜像和两份 lint 日志。最初展开遇到的全局停顿组合环证据另存 integration-loop.log，
修复与专项回归见 [IFU 报告](14-bus-ifu.md)。

范围：本模块测试为 SoC smoke，未将其替代完整程序矩阵、Spike 差分、独立 C++ RTL
验收或代码覆盖；这些由最终第九章 Test Plan 单独记录。无 RV64GC、cache burst、
atomic 或 AHB ERROR 支持，不声称完成综合时序收敛。
