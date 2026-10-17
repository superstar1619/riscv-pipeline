# 11 APBRegisterBank

验证用最小 APB 从设备，显式 clk/reset 与 Bool 同步复位。PADDR 为完整窗口内字节偏移；
Uncore 负责从桥的物理地址减去 apbBase。寄存器按32位字译码：字节偏移0..3
属于按 PSTRB 更新的 scratch，4..7 属于只读写完成计数；其他寄存器字读零且不修改 scratch。
计数精确定义为所有完成的 APB 写 ACCESS，包括零 strobe、写只读计数地址和未定义地址；
这些写的 PWDATA 不会直接改变计数值。每次事务仅在 PSEL && PENABLE && PREADY
完成时产生一次副作用，Setup 和等待周期均不更新，完成后持续保持 Access 不重复执行。
额外等待 0..255 拍；未实现第20章完整外设。

TDD 缺类红阶段见 `target/chapter9/modules/apb-register-bank/red.log`。
定向测试 4/4 通过，涵盖等待 0/1/2、逐字节写、读取、零 strobe、只读/未定义偏移写计数、
4096 偏移不别名、Setup 无副作用、持续 Access 不重复及同步复位。
GenerateAPBRegisterBank 以等待 2 生成成功，Verilator lint 退出 0。
证据为同目录 green.log、lint.log、rtl/APBRegisterBank.v。未执行整机回归。


## CPU 真实子字访问发现的修正

整机独立 RTL 程序 apb-subword 首次运行暴露真实缺陷：初始 scratch=0x11223344，
CPU 随后执行 scratch+1 的 SB（HWDATA=0xaaaaaaaa、HWSTRB=2）及 scratch+2 的 SH
（HWDATA=0xbbccbbcc、HWSTRB=12）。总线字节通道和写完成计数3正确，但读取 scratch
仍为0x11223344。原实现使用 PADDR==0 的精确字节地址判断，将合法的子字地址误当成
未定义寄存器；原单元测试仅在 PADDR=0 处更改掩码，未覆盖真实低位地址。
首次失败证据为
`target/chapter9-testplan/runs/20261017T011958432347Z/rtl/bus-only-ram0/apb-subword/actual-s1-r0.json`。

修正使用完整 PADDR(31,2) 选择寄存器字，PSTRB 原样选择物理字节通道。
仅忽略两个字内偏移位，完整高位仍参与译码，所以偏移4096/4097不会别名到 scratch。
不改变完成条件、计数语义、等待状态或复位。

先增加等待0/1/2的真实地址单元测试及 Uncore→bridge 集成测试，原实现14项中8项原有
测试通过、6项新增测试失败（都在首次 SB 后读回旧值），保留 TDD 红证据。
修正后 APBRegisterBank 7/7、Uncore 7/7、生产 SoC 4/4，合计 **18/18通过**。
新增测试检查 SB 后0x1122aa44、SH 后0xbbccaa44、写计数3，以及完整高位不别名；
Uncore 集成还使用真实 HSIZE=0/1 传递字节/半字地址。
三个生成入口 APBRegisterBank、Uncore、RiscvPipelineSoC 成功，均以等待2生成；
三个 Verilator lint 全部退出0。
证据集中在 `target/chapter9/modules/apb-register-bank/subword-fix/`：red.log、green.log、
lint-bank.log、lint-uncore.log、lint-soc.log 和三个 rtl-* 目录。
完整144项 RTL矩阵由平台实现方在提交修正后重新运行，本报告不预先宣称其结果。
