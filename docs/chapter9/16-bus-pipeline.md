# BusPipeline 整体流水线接入

BusPipeline 保留原六个 M 级观察端口，增加 AHB 主口和同域 interrupt/time 输入。
PipelineBase 只在 `config.bus` 非空时实例化 EBU 与 BusHazardControl，IFU/LSU 的
响应分别由仲裁归属返回；默认无总线配置沿用原 IROM/DTIM 与特权连接。

IFU/LSU 等待冻结 F/D/E/M，W 级按既有机制排空一次。特权单元接入 CommittedF/M，
不可撤回的事务完成后才允许异常或中断。取消使用独立于最终停顿的 Trap、Ret、
CSRWrite、fence 候选；实际 RedirectM 只在 M 级可推进时生效。

首次整机 elaboration 检出了实际 RedirectM→取指取消→BusStall→StallM→RedirectM
组合回路。BusIFU 已改为由 Data 状态和未提交地址意图导出前端停顿，实际重定向仍可
取消地址。独立用例验证该边界，生成 RTL 的组合回路检查和 Verilator lint 均通过。

2026-10-17 验证：恒零 AHB 顶层在三个等待配置均因无法完成程序而失败；接入后
BusPipelineSpec 3/3 通过，额外 AHB 等待为 0/1/3。程序写 42、读取后加一并写 43，
同时检查物理写和架构写均恰好两次、依赖结果 x4 恰好写回一次、完成后观察十二拍。
联合 BusIFUSpec 7/7 通过。GenerateBusPipeline 成功；Verilator `-Wall` 通过，
仅排除生成文件多模块命名和未使用内部信号两类告警。

证据目录：`target/chapter9/modules/bus-pipeline/`。精确异常、中断、设备切换和
两种存储配置由 SoC 验收进一步覆盖；本模块测试不冒充完整整机 Test Plan。
