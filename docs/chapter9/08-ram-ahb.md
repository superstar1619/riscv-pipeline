# 08 RAMAHB

参考 CVW `uncore/ram_ahb.sv`；显式 clk/reset，Bool 同步复位。
RAM 为不预初始化的 32 位 SyncReadMem，使用单个 masked readWrite 端口，
HWSTRB(0) 对应最低字节。参数为字节基址、字节容量和 0..255 拍额外等待。

地址接受条件严格为 HSEL && HTRANS[1] && HREADY。读在地址接受沿发往同步存储器，
无额外等待时下一数据相位可完成；输出通过有效读标志和保持寄存器跨等待/空闲保持。
写只在数据相位完成沿产生一次物理写使能；写后同沿接受读时，先完成写，下一沿读，
插入一拍单端口结构等待。额外等待在结构等待之后计数。
复位取消待处理事务但不清空 RAM；复位在存储器时序副作用上有优先级，
未增加组合请求/响应的 !reset 门控。

TDD 缺类编译失败保存在 `target/chapter9/modules/ram-ahb/red.log`。
定向测试 6/6 通过：额外等待 0/1/2、整字与混合字节写、同步读及空闲保持、
写后读结构冲突、写完成唯一脉冲（测试 harness 暴露内部 WriteEnable）、
未接受地址忽略及同步复位。测试不假定未写 RAM 内容。
GenerateRAMAHB 以额外等待 2 生成成功，Verilator lint 退出 0。
证据位于该目录的 green.log、lint.log 与 rtl/RAMAHB.v。

复现使用统一 sbt 锁和本地脚本，测试源限本 worker 的 uncore 与 ProjectTest，
运行 `testOnly riscvpipeline.uncore.RAMAHBSpec`、
`runMain riscvpipeline.generators.GenerateRAMAHB target/chapter9/modules/ram-ahb/rtl 2`。
未执行整机回归或工艺 RAM 综合映射。
