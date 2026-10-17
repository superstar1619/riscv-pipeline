# 09 ROMAHB

参考 CVW `uncore/rom_ahb.sv`，保留原生 SyncReadMem 一拍读延迟。
ROM 参数为字节基址、字节容量、可选 hex32 初始化文件、0..255 拍额外等待。
地址仅在 HSEL && HTRANS[1] && HREADY 接受；读结果跨等待及空闲保持。
写请求返回 OKAY 但不修改 ROM。未提供镜像时存储内容未定义，不能当作零初始化。
显式 clk/reset，寄存器 Bool 同步复位，镜像不随复位重载，没有组合 !reset 门控。

TDD 缺类失败见 `target/chapter9/modules/rom-ahb/red.log`。
定向测试 3/3 通过，使用独立 hex32 镜像验证等待 0/1/2、首中末字、地址改变时响应保持、
空闲保持及 ROM 写忽略。GenerateROMAHB 使用等待 2 生成成功，Verilator lint 退出 0。
测试/生成与 lint 日志分别为该目录 green.log、lint.log，RTL 在 rtl/ROMAHB.v。
未执行整机回归或工艺 ROM 映射。
