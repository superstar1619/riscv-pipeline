# BusLSU 与 LSU 接入

CpuConfig 末尾增加可选 bus，默认 None；LSU 在 Some 时委托 BusLSU，旧 DTIM 分支保留
原行为。LSUIO 新增 StallM、BusFlushM、BusStallM、BusCommittedM 与 AHBMasterIO；
PipelineBase 为新增输入连接安全常量，后续整机接入再连接总线与统一停顿。

BusLSU 复用 AHBInterface、子字读写、EndianSwap 和可选 DTIM。BootROM 只读，RAM/APB
可读写；DTIM 启用时位于零地址窗口。未映射、未对齐和 ROM 写在发请求前检测。
AHB 是单次 NONSEQ、32 位数据、34 位零扩展地址；HSIZE 来自实际字节/半字/字宽。
BusRW 仅依赖 M 级访存意图与地址合法性，独立于总 StallM/SuppressM；BusFlushM
取消尚未接受的请求，已接受 Data 继续完成，Mem3 保持到流水线消费。

DTIM 延续 E 级同步读、M 级写：响应到达拍直接旁路原生字，同时捕获保持值，避免
IFU 总线等待令同步读输出失效；未增加正常读延迟。读格式化仍使用 M 级子字与端序。
写字节掩码使用物理地址偏移，写数据复制后按端序转换。io.ahb 保存真实总线写载荷，
ByteMaskM 仅在 Mem3 消费且未 SuppressM 时报告架构 store 事件，防止等待重复报告。
新增逻辑仅同步复位寄存器，不增加组合 reset 门控。

2026-10-17 TDD：根代理先提供 scaffold 与五项测试，red.log 证明五项均按功能预期
失败；实现后 BusLSUSpec 5/5 通过：停顿中总线读完成、接受后子字写载荷保持、
本地同步响应跨 IFU 停顿、访问故障先于发请求、大小端与子字读的独立预期值。

统一使用 `flock target/chapter9-sbt.lock env JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx3g'
./scripts/sbt-local.sh`，临时 Test/sources 仅选择 BusLSUSpec 与 ProjectTest，运行
`testOnly riscvpipeline.lsu.BusLSUSpec`。GenerateBusLSU 分别生成纯总线和带 DTIM 的 RTL，
两者 `verilator --lint-only -Wno-fatal --top-module BusLSU` 成功且无 RTL 警告。
证据目录：`target/chapter9/modules/bus-lsu/`，含 red/green 日志、rtl/、tim/ 与 lint 日志。

范围：没有 burst、atomic 或 AHB ERROR；整机异常、中断与旧路径回归由整体验收执行。
