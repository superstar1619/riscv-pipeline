# 09 ROMAHB

参考 CVW `uncore/rom_ahb.sv`，保留原生 SyncReadMem 一拍读延迟。
ROM 参数为字节基址、字节容量、可选 hex32 初始化文件、0..255 拍额外等待。
地址仅在 HSEL && HTRANS[1] && HREADY 接受；读结果跨等待及空闲保持。
写请求返回 OKAY 但不修改 ROM。未提供镜像时存储内容未定义，不能当作零初始化。
显式 clk/reset，寄存器 Bool 同步复位，镜像不随复位重载，没有组合 !reset 门控。

## 2026-10-21：存储器 CE 常开

检查当前工程全部四个 `SyncReadMem` 实现后，ROMAHB 与 IROM 一并将物理读端口的
`en` 固定为 `true.B`。本地 `~/cvw/src/uncore/rom_ahb.sv` 的 ROM 端口同样采用
`.ce(1'b1)`。ROMAHB 的逻辑 `ReadEnable = Accept && !HWRITE`、`ReadIssued`
和 `HeldData` 保留，空闲或等待期间内部地址变化不会污染有效 HRDATA；总线等待、
写请求 OKAY 完成但忽略、镜像初始化与同步复位契约保持不变。

IROM 的公开 `io.en` 继续控制有效响应与输出保持；物理 CE 与之分开。CVW IROM
仍公开可控 `ce`，不能将本次常开调整写成 CVW IROM 原有实现。已有 ROMAHB 三项
用例覆盖等待0/1/2、地址变化与写忽略，PipelineMemory 用例覆盖 IROM 禁用期间
地址变化而输出保持。本轮关联 Scala147/147通过，两个 ROM 使用完整镜像生成后
严格 lint 均退出0，最终整机矩阵144/144通过；未初始化配置的 UNDRIVEN 警告及完整证据见
[存储器 CE 常开验证报告](22-ram-ce.md)。

## 初版验证记录

TDD 缺类失败见 `target/chapter9/modules/rom-ahb/red.log`。
定向测试 3/3 通过，使用独立 hex32 镜像验证等待 0/1/2、首中末字、地址改变时响应保持、
空闲保持及 ROM 写忽略。GenerateROMAHB 使用等待 2 生成成功，Verilator lint 退出 0。
测试/生成与 lint 日志分别为该目录 green.log、lint.log，RTL 在 rtl/ROMAHB.v。
本节初版模块验证未执行整机回归或工艺 ROM 映射；后续整机运行见
[完整实验报告](20-lab-report.md)及 [存储器 CE 常开验证报告](22-ram-ce.md)。
