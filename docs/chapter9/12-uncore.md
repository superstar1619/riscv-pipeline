# 12 Uncore

参考 CVW `uncore/uncore.sv`，组合 AdrDecs、ROMAHB、RAMAHB、AHBAPBBridge 和最小
APBRegisterBank；外侧 io.ahb 是 Flipped(AHBMasterIO)，显式 clk/reset 共用同步时钟域。
地址相位由组合译码选择设备，响应则使用只在全局 HREADY 上升沿更新的 Selected。
等待期间即使 HADDR 指向其他设备，也保留当前数据相位的响应归属。
同步复位 Selected=0001（None），保证 HREADY=1、HRDATA=0、HRESP=0；
未映射或不支持尺寸也返回 None 的 OKAY 零响应，不实现 ERROR、突发或缓存。
桥传出物理地址，接银行时减去配置 apbBase 的低32位，完整窗口相对偏移不会每4KiB别名。
RAM 不预初始化；BootROM 可配置 hex32 镜像。

TDD 缺类编译失败见 `target/chapter9/modules/uncore/red.log`。
Uncore 定向测试 4/4 通过，覆盖额外等待 0/1/2、ROM 镜像读、RAM 掩码写读、
APB scratch/计数、8KiB APB 窗口不别名、未映射访问和 HRESP。
跨设备测试在 RAM 等待期间提前给出有效 APB 下一地址，检查 Selected 保持至 RAM 完成，
随后 APB 进入 Setup；在该待处理事务中同步复位回到 None，避免复位死锁。

最终同时运行七个 uncore/foundation 测试套件，合计 23/23 通过；
GenerateUncore 以所有设备额外等待 2 生成成功，Verilator lint 退出 0。
该目录 green.log 保存测试/生成证据，lint.log 保存 lint，rtl/Uncore.v 是生成文件。
使用统一 sbt 锁与 Test 源过滤，执行 `testOnly riscvpipeline.uncore.*` 和
`runMain riscvpipeline.generators.GenerateUncore target/chapter9/modules/uncore/rtl 2`。
本模块未执行完整 CPU 回归；整机程序、陷入和真实退休轨迹由上层集成验收。
