# AHBInterface

实现非突发、非原子的 RV32 AHB 接口，平坦端口见 [共享契约](interfaces.md)。
组合与状态接口沿用 CVW `src/ebu/ahbinterface.sv`，源码保留版权和许可证。
`readOnly=false` 为 LSU，`true` 为 IFU；均采用显式 clk、Bool 同步 reset。

CVW 每拍保存写载荷，本工程改为仅在地址接受沿保存 WriteData/ByteMask，避免
Data 等待期间上游改变载荷。FetchBuffer 仅在 BusFSM CaptureEn 更新；Stall、Flush
不丢弃已接受响应；Mem3 保持结果且不重发。只读实例写数据和掩码固定零。
reset 仅影响寄存器，不增加组合 `&& !reset`。

2026-10-17 验证：先运行 AHBInterfaceSpec，因生产类型不存在预期失败；实现后
5/5 测试通过，覆盖两种模式下的读等待、完成捕获、Stall/Flush 保持、写载荷接受与
保持、只读写抑制及未接受写取消。另两叶模块同时运行，共 12/12 通过。

使用 `flock target/chapter9-sbt.lock env JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx3g'
./scripts/sbt-local.sh 'testOnly riscvpipeline.ebu.AHBInterfaceSpec'`；实际联合通过日志为
`target/chapter9/modules/ahb-interface/green.log`。GenerateAHBInterface 分别生成
`lsu/`、`ifu/` 两种 RTL；两者 `verilator --lint-only -Wno-fatal --top-module AHBInterface`
成功，无 RTL 警告。生成和 lint 日志均保存在同目录；环境启动的 stream fd 提示与 RTL 无关。

范围：无 burst、atomic、ERROR；未验证综合面积、时序收敛或 RV64。
