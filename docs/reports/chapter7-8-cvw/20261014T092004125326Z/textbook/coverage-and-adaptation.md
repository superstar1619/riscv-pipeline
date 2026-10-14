> 历史证据已迁至 [target 中的证据目录](../../../../../target/reports/chapter7-8-cvw/20261014T092004125326Z/textbook/)。`make clean` 会删除原始证据，Markdown 报告保留；重新运行相应报告命令将产生新的一次运行。

# 第七、八章 CVW 本轮回归入口

在项目根目录执行：

```bash
make report-chapter7-8-cvw SBT=./scripts/sbt-local.sh
# 仅执行新增的 CVW 第八章原始样例
make test-chapter8-cvw SBT=./scripts/sbt-local.sh
```

复用 `CVW_ROOT`、`SBT`、`VERILATOR`、`SPIKE`、`RISCV_TOOL_PREFIX`。默认上游 `/home/unlastingstar/cvw`，仅作读取。上游 CVW revision 固定为 `2f82f001139358be7646e910ff6fa2a140bed620`；第七章架构测试与 CoreMark revision 沿用既有构建器。逐个源文件与 Git blob 比较，不使用旧 ELF 或旧结果替代本轮执行。

每轮创建 `target/chapter7-8-cvw/runs/<UTC>/`；联合入口生成结果后归档到 `docs/reports/chapter7-8-cvw/<UTC>/`。`latest-run.txt` 仅为运行目录定位文件。旧章节报告与旧 current-run 指针均不覆盖。失败命令记录退出码并继续独立测试；依赖构建失败的项目记录未执行原因。全部适用项通过时退出 0，其他情况退出非零。结果行区分 `pass`、`cpu_failure`、`timeout`、`build_platform_error`、`not_applicable` 和 `not_executed`。

全量 Scala 只运行一次，通过独立 JUnit 目录逐测试名按章节汇总。`test-acceptance` 的重复执行用于核验既有入口，`counted=false`，不能增加独立功能覆盖。RTL 的种子和复位重跑次数与 Scala 用例数分开统计。

## 教材与上游覆盖

教材依据为工作区 `RISC-V System-on-Chip Design, Edition 1.pdf` 第八章 §8.3、表 8.5–8.9（印刷页 360–366）。上游目录均为 `tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/`，下表文件名省略 `src/` 和 `WALLY-`/`-01.S`。

|教材要求|上游样例|平台适配|保留的检查项|
|---|---|---|---|
|表8.6 机器信息 CSR|minfo|删除无实现的 PMP 启动写|五个机器信息 CSR 可读、写陷阱、值未变|
|表8.6 misa|misa|启动、结束|misa 非零；结束 ecall|
|表8.5/8.6 mtvec|mtvec|定时中断标记|非向量入口、cause/tval/status|
|表8.5/8.6 stvec|stvec|精确 xepc 恢复|S 非向量入口，S/U 来源及状态栈|
|表8.6 mie、表8.9|mie|六类触发映射至引脚或 pending CSR|mie=0 时不误触发|
|表8.6 sie、表8.9|sie|默认软件 STIP、扩展 Sstc|sie=0 时不误触发|
|表8.6 CSR 权限|csr-permission-u|真实异常返回|U 对机器/S CSR 的每次读写均陷阱|
|表8.6 CSR 权限|csr-permission-s|真实异常返回|S 对机器 CSR 每次读写均陷阱|
|表8.5–8.9 M 陷阱|trap|0→窗口外，外设→中断输入|M 异常、六类中断、向量标记、委派限制；默认配置因显式 Sstc 不适用|
|表8.5–8.9 S 陷阱|trap-s|异常地址、Bare 委派掩码、收尾恢复 M|S/U 陷阱、M/S 委派、向量和状态栈|
|表8.5–8.9 U 陷阱|trap-u|同上|U 陷阱、M/S 委派、向量和状态栈|
|表8.5 SRET、表8.7 TSR|trap-sret|独立编写缺失的参考签名|TSR=1 的 SRET 非法指令、tval 和状态|
|表8.7 MIE|status-mie|MSIP 存储标记在提交后拉高引脚|MIE 开关、MPIE/MPP|
|表8.7 SIE|status-sie|SSIP 保留为真实 CSR|SIE 开关、SPIE/SPP|
|表8.5 WFI、表8.7 TW|status-tw|保持默认 65536 周期 TW 超时|S 模式 WFI 非法指令、tval 和状态|
|表8.7 TVM|status-tvm|Bare 平台限定为访问限制|satp 读写及 sfence.vma 非法；不宣称地址翻译覆盖|
|表8.7 MBE/SBE/UBE|endianness|数据临时地址迁至 0xf800|M/S/U 大小端 word/half/byte 真正存储及读回|
|表8.5 WFI、表8.9 定时中断|wfi|延后 96 周期定时引脚|等待后先记录中断再写 good 标记|

表8.8 的指令访问、非法指令、断点、load/store 非对齐和访问异常以及 M/S/U ecall 都由三个 trap 样例保留。原始样例未调用指令地址非对齐触发器，因此不扩大这三个样例的覆盖声明；已有 Scala/RTL 对 IALIGN 的测试独立列报。页异常由以下 MMU 不适用项说明。表8.9 的六类中断覆盖 CPU 输入端和 pending CSR 语义，不验证 CLINT、PLIC 或 GPIO 外设本体。

以下十项均保留原始源文件和不适用理由：

|样例|不适用原因|
|---|---|
|mmu-sv32|需要 Sv32 页表及地址翻译|
|mmu-sv32-svadu|需要 Sv32 与自动 A/D 更新|
|satp-invalid|需要无效地址翻译引发取指故障|
|pmp|需要 PMP 寄存器和权限匹配|
|pma|需要 PMA 属性及外设存储映射|
|status-fp-enabled|需要浮点寄存器和 F/D 指令|
|lrsc|需要 A 扩展及 LR/SC 保留集|
|cbom|需要 Zicbom 缓存管理|
|cboz|需要 Zicboz 缓存块清零|
|pwm|需要 PWM/GPIO/PLIC 外设|

## 适配与预期来源

原始汇编、完整测试库、参考输出、许可证和 SHA-256 保存在每次运行 `chapter8-cvw/programs/upstream/`。库和主体的 unified diff、ELF、符号表、反汇编、镜像、参考与每轮实际签名分别随配置/程序保存。上游没有参考输出的 minfo、misa、U/S CSR 权限与 SRET 样例，以 CSR 权限规则和汇编语义编写预期。其他样例读取上游带注释的参考，按 ELF 符号重定位 breakpoint/非对齐 tval、将访问错误地址改为 0x10000，并按 Bare 范围修正 medeleg 掩码。预期生成完全先于 DUT 执行。

IROM 与 DTIM 均为 64 KiB。链接器把代码限制在 `[0,0xc000)`，初始化数据放在 `[0xc000,0xf800)`，大小端临时数据位于 `0xf800`，协议位于 `0xff00` 之后，并检查容量和地址重叠。启动通过真实 SW 重建整个数据区（含查找表、栈、1024 字签名哨兵），复位不假定 RAM 清零。结束通过真实 LW 从 DTIM 读回所有 1024 字，并检查实际使用长度、前置 `0xcafef00d` 与后置零哨兵。

保留原始陷阱日志，异步返回使用准确的 xepc。结束 ecall 在 M 或 S 处理器记录原签名后收尾；若 ecall 被委派到 S，则用机器软件中断转入 M，平台收尾专用入口不向签名写额外向量标记。随后关闭中断并恢复小端，再流式读回签名、写完成标记并观察后续八拍。

协议 `0xff20` 写 3/7/9/11 表示 M 软件/M 定时/S 外部/M 外部中断；提交后的下一拍拉高对应同步输入，写零撤销。写 5 驱动 Sstc 时间越过比较器；WFI 专用 0x107 延后 96 拍触发。默认配置的 S 定时按教材表8.9 软件设置 STIP。它们只替换外设触发/应答；CPU 正常译码、CSR、陷阱与存储通路均保持原实现。
