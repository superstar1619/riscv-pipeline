# 08 RAMAHB

参考 CVW `uncore/ram_ahb.sv`；显式 clk/reset，Bool 同步复位。
RAM 为不预初始化的 32 位 SyncReadMem，使用单个 masked readWrite 端口，
HWSTRB(0) 对应最低字节。参数为字节基址、字节容量和 0..255 拍额外等待。

地址接受条件严格为 HSEL && HTRANS[1] && HREADY。读在地址接受沿发往同步存储器，
无额外等待时下一数据相位可完成；输出通过有效读标志和保持寄存器跨等待/空闲保持。
写只在数据相位完成沿产生一次物理写使能；写后同沿接受读时，先完成写，下一沿读，
插入一拍单端口结构等待。额外等待在结构等待之后计数。
复位取消待处理事务并抑制写入，但不清空 RAM；未增加组合请求/响应的 !reset 门控。

## 2026-10-21：RAM CE 常开

对照本地 `~/cvw/src/uncore/ram_ahb.sv` 的 `.ce(1'b1)`，原生 `readWrite` 的
`en` 改为无条件 `true.B`，复位期间也保持 CE=1。逻辑 `ReadEnable`、`ReadIssued`
和 `HeldData` 继续限定有效响应；空闲地址变化可以触发内部读，HRDATA 仍保持上次有效读值。
原先包围整个存储器访问的复位条件移到 `PortWrite`，复位时写模式为 false，其余周期
保持 `WriteEnable`。写完成时机、HWSTRB 字节掩码和写后读结构等待保持原有契约。

CE 控制存储器端口活动，WE 控制是否写入，HWSTRB 控制写入的字节通道。CE=1 不会
使空闲 HWRITE 或 HWSTRB 产生写入。生成 RTL 将 readwriter 拆成读/写信号时，读侧
`r_en` 仍可随写模式和有效字节掩码变化。本轮生成的 CHIRRTL `.fir` 将 `rdwr mport`
放在常量1条件下，Verilog 也保留常量1的总端口使能因子。
DTIM、IROM 和 ROMAHB 的同类检查与调整，以及验证证据见
[存储器 CE 常开验证报告](22-ram-ce.md)。

新增两项行为回归检查完成沿复位取消写入，以及空闲 HADDR/HWRITE/HWSTRB 变化时
HRDATA 保持且存储内容不变。修改前 RAMAHB 8/8、PipelineMemory 5/5 通过；范围
扩展至 IROM/ROMAHB 后，关联 Scala147/147、23个套件及五种独立 RTL 的生成与
严格 lint 均通过。ROM 的 lint 使用完整初始化镜像，整机矩阵144/144及独立报告、
SHA256SUMS 复核均通过；具体证据见
上述验证报告。

## 初版验证记录

TDD 缺类编译失败保存在 `target/chapter9/modules/ram-ahb/red.log`。
定向测试 6/6 通过：额外等待 0/1/2、整字与混合字节写、同步读及空闲保持、
写后读结构冲突、写完成唯一脉冲（测试 harness 暴露内部 WriteEnable）、
未接受地址忽略及同步复位。测试不假定未写 RAM 内容。
GenerateRAMAHB 以额外等待 2 生成成功，Verilator lint 退出 0。
证据位于该目录的 green.log、lint.log 与 rtl/RAMAHB.v。

复现使用统一 sbt 锁和本地脚本，测试源限本 worker 的 uncore 与 ProjectTest，
运行 `testOnly riscvpipeline.uncore.RAMAHBSpec`、
`runMain riscvpipeline.generators.GenerateRAMAHB target/chapter9/modules/ram-ahb/rtl 2`。
本节初版模块验证未执行整机回归或工艺 RAM 综合映射；后续整机运行见
[完整实验报告](20-lab-report.md)及 [存储器 CE 常开验证报告](22-ram-ce.md)。
