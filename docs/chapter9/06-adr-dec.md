# 06 AdrDec 与总线基础类型

实现 CVW `mmu/adrdec.sv` 的 34 位组合掩码地址译码。`Range` 为容量减一；
Supported、AccessValid、SizeMask 共同决定 Sel。没有状态、时钟或复位端口。
同时落地共享 AHB/APB Bundle 和 BusConfig，配置检查窗口对齐、范围、不重叠及等待上限。

TDD：先运行缺少 AdrDec/BusConfig 的测试，编译失败见
`target/chapter9/modules/adr-dec/red.log`。补实现后定向测试 2/2 通过，覆盖
高于 32 位地址、窗口两端、窗口外地址、访问尺寸、使能与非法配置。
`green.log` 保存测试和 GenerateAdrDec 结果；`rtl/AdrDec.v` 为生成 RTL，
`lint.log` 为 Verilator lint（退出 0）。未运行整机回归，留待集成阶段。

复现：串行锁保护下运行 `testOnly riscvpipeline.uncore.AdrDecSpec` 与
`runMain riscvpipeline.generators.GenerateAdrDec target/chapter9/modules/adr-dec/rtl`，
随后 `verilator --lint-only --top-module AdrDec target/chapter9/modules/adr-dec/rtl/AdrDec.v`。
