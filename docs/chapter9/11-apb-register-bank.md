# 11 APBRegisterBank

验证用最小 APB 从设备，显式 clk/reset 与 Bool 同步复位。PADDR 为完整窗口内字节偏移；
Uncore 负责从桥的物理地址减去 apbBase。offset0 是按 PSTRB 更新的 scratch；
offset4 只读显示写完成次数；其他偏移读零且不修改 scratch。
计数精确定义为所有完成的 APB 写 ACCESS，包括零 strobe、写只读计数地址和未定义地址；
这些写的 PWDATA 不会直接改变计数值。每次事务仅在 PSEL && PENABLE && PREADY
完成时产生一次副作用，Setup 和等待周期均不更新，完成后持续保持 Access 不重复执行。
额外等待 0..255 拍；未实现第20章完整外设。

TDD 缺类红阶段见 `target/chapter9/modules/apb-register-bank/red.log`。
定向测试 4/4 通过，涵盖等待 0/1/2、逐字节写、读取、零 strobe、只读/未定义偏移写计数、
4096 偏移不别名、Setup 无副作用、持续 Access 不重复及同步复位。
GenerateAPBRegisterBank 以等待 2 生成成功，Verilator lint 退出 0。
证据为同目录 green.log、lint.log、rtl/APBRegisterBank.v。未执行整机回归。
