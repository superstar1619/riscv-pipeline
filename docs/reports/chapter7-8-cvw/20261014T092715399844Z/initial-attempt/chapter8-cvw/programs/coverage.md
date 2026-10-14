> 历史证据已迁至 [target 中的证据目录](../../../../../../../target/reports/chapter7-8-cvw/20261014T092715399844Z/initial-attempt/chapter8-cvw/programs/)。`make clean` 会删除原始证据，Markdown 报告保留；重新运行相应报告命令将产生新的一次运行。

# 教材第八章覆盖表

|教材要求|上游文件|适配内容/不适用理由|检查项|
|---|---|---|---|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-minfo-01.S|原主体、陷阱签名；平台启动/地址/中断/结束协议适配|minfo|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-misa-01.S|原主体、陷阱签名；平台启动/地址/中断/结束协议适配|misa|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-mtvec-01.S|原主体、陷阱签名；平台启动/地址/中断/结束协议适配|mtvec|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-stvec-01.S|原主体、陷阱签名；平台启动/地址/中断/结束协议适配|stvec|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-mie-01.S|原主体、陷阱签名；平台启动/地址/中断/结束协议适配|mie|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-sie-01.S|原主体、陷阱签名；平台启动/地址/中断/结束协议适配|sie|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-csr-permission-u-01.S|原主体、陷阱签名；平台启动/地址/中断/结束协议适配|csr-permission-u|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-csr-permission-s-01.S|原主体、陷阱签名；平台启动/地址/中断/结束协议适配|csr-permission-s|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-trap-01.S|原主体、陷阱签名；平台启动/地址/中断/结束协议适配|trap|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-trap-s-01.S|原主体、陷阱签名；平台启动/地址/中断/结束协议适配|trap-s|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-trap-u-01.S|原主体、陷阱签名；平台启动/地址/中断/结束协议适配|trap-u|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-trap-sret-01.S|原主体、陷阱签名；平台启动/地址/中断/结束协议适配|trap-sret|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-status-mie-01.S|原主体、陷阱签名；平台启动/地址/中断/结束协议适配|status-mie|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-status-sie-01.S|原主体、陷阱签名；平台启动/地址/中断/结束协议适配|status-sie|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-status-tw-01.S|原主体、陷阱签名；平台启动/地址/中断/结束协议适配|status-tw|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-status-tvm-01.S|原主体、陷阱签名；平台启动/地址/中断/结束协议适配|TVM: satp 读写和 sfence.vma 访问限制；无地址翻译|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-endianness-01.S|原主体、陷阱签名；平台启动/地址/中断/结束协议适配|endianness|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-wfi-01.S|原主体、陷阱签名；平台启动/地址/中断/结束协议适配|wfi|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-mmu-sv32-01.S|需要 Sv32 页表与地址翻译，当前为 Bare|mmu-sv32|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-mmu-sv32-svadu-01.S|需要 Sv32 与 Svadu 自动 A/D 更新|mmu-sv32-svadu|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-satp-invalid-01.S|需要实际地址翻译及取指页故障|satp-invalid|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-pmp-01.S|需要 PMP 寄存器及权限匹配单元|pmp|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-pma-01.S|需要 PMA 属性及设备存储映射|pma|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-status-fp-enabled-01.S|需要浮点寄存器及 F/D 指令|status-fp-enabled|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-lrsc-01.S|需要 A 扩展保留集及 LR/SC|lrsc|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-cbom-01.S|需要 Zicbom 和缓存管理|cbom|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-cboz-01.S|需要 Zicboz 缓存块清零|cboz|
|§8.3 表8.5–8.9|tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src/WALLY-pwm-01.S|需要 PWM/GPIO/PLIC 外设|pwm|
