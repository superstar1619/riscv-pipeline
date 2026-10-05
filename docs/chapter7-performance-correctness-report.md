# RV32I 五级流水线处理器性能测评与功能正确性验证报告

**报告日期：2026-10-05**

**项目：riscv-pipeline / RiscvPipeline**

**对应工程版本：`9847509`，分支 `feat/chapter7-pipeline`**

**依据：教材 §7.2–7.4、当前设计契约、已完成的 Scala/Verilator 测试及 Spike 参考签名。**

本报告整理此前完成的实测结果，并重新核对原始日志、程序清单、签名长度、随机种子
和性能计算。报告编写期间没有重新运行硬件基准，也没有修改生产 RTL 或测试行为。
归档证据随报告保存，详见第 12 节。

## 1. 测评结论

当前实现已通过约定 RV32I 范围内的模块测试、流水线定向测试、整机访存事件验收、
教材 §7.4 架构签名验证，以及 CoreMark 的固定迭代功能和周期验证。

| 验证层次 | 实际规模 | 结果 |
| --- | --- | --- |
| 完整活动 Scala 回归 | 14 个测试类、122 项测试 | 122 通过，0 失败 |
| 单独整机验收入口 | 2 项架构验收 + 6 项 smoke | 8 通过，0 失败；已包含在上述 122 项内 |
| 小程序 RTL 验收 | 4 配置 × 3 随机种子 × 2 次复位启动 | 12 个进程、24 次执行，全部通过 |
| §7.4 架构测试 | 39 程序 × 3 随机种子 | 117 次执行，完整签名一致 |
| CoreMark | 2 模式 × 3 随机种子，2000 字节、10 次迭代 | 6 次执行，CRC 正确，错误数为零 |
| 架构/CoreMark 最终合并入口 | 41 程序 × 3 随机种子 | 123 次执行，全部通过 |
| 上游清单完整性回归 | 缺失源文件、缺失头文件、未知名称、完整清单 | 4 项通过 |
| 检查器负向验证 | 篡改参考签名、仅允许 1 周期 | 均拒绝，未产生错误通过 |

CoreMark 性能种子的测量区间为 **10,533,211 周期**，平均 **1,053,321.1 周期/迭代**；
验证种子为 **11,331,447 周期**，平均 **1,133,144.7 周期/迭代**。
同一模式的三个随机初态下，CRC、完成周期和测量区间周期完全相同。

这些结果支持“当前设计在已验证输入、配置与运行契约下具有功能正确性，能够执行
RV32I 软件并给出可重复的周期结果”。本报告不将有限测试通过等同于形式化证明或
全 ISA 认证；也不报告官方 CoreMark 得分、最高频率、功耗、面积或单周期对比加速比。

## 2. 被测设计与适用范围

### 2.1 流水线结构

| 阶段 | 主要行为 | 与正确性相关的约束 |
| --- | --- | --- |
| F | 保存 PC、默认顺序取指 | E 级重定向优先于停顿 |
| D | 接收同步 IROM 响应、译码、读寄存器 | 指令与 PCD 对齐；W→D 旁路 |
| E | 运算、分支判断、跳转目标计算、DTIM 读地址 | M→E 优先于 W→E；store 数据、比较和 JALR 基址均前递 |
| M | 保存执行结果、访存信息；提交存储请求 | 同步读响应与子字控制对齐；实际写入由掩码决定 |
| W | 保存结果并写回寄存器文件 | 上升沿写入；排除 x0；有效位和复位控制写允许 |

生产顶层保留显式 `clk/reset` 和六个观察输出，共 8 个端口。
`WriteData/IEUAdr/MemWrite/MemRW/Funct3/ByteMask` 位宽分别为
32/32/1/2/3/4，均报告 M 级信息。`MemRW` 报告读写请求，
`MemWrite/ByteMask` 报告实际允许的存储。

复位为高有效同步状态复位：PC 和各级有效位在上升沿更新；复位信号同时抑制
架构写允许。RAM 与非零寄存器保留既有内容，未写 RAM 内容未指定。

### 2.2 指令与系统边界

被测 CPU 固定 XLEN=32，覆盖现有整数算术、逻辑、移位、比较、分支、JAL/JALR
及字节/半字/字访存。当前系统使用独立 IROM 和 DTIM，没有外部访存接口。

乘除法由 RV32I 软件函数执行；没有增加 M 扩展、CSR、ECALL/EBREAK、异常处理、
中断、缓存、虚拟存储或分支预测。FENCE 在当前单核、顺序内部 RAM、无外部 I/O 的
系统中无附加操作，其顺序访存场景已验证。FENCE.I、外部总线屏障及多核一致性未验证。

未对齐或非法子字访问按照项目契约不执行实际存储，加载返回零；这不是异常处理
实现。基础模块中的 64 位参数测试不代表 CPU 支持 RV64。

## 3. 环境、版本与被测配置

### 3.1 软件环境

| 工具/来源 | 实际版本或固定 revision |
| --- | --- |
| JDK | Eclipse Adoptium 17.0.20.1 |
| sbt / Scala | 1.10.7 / 2.13.14 |
| Chisel / chiseltest | 3.6.1 / 0.6.2 |
| RISC-V GNU GCC | `riscv64-unknown-elf-gcc (g5115c7e44) 15.2.0` |
| Verilator | 5.036，随机初态模拟 |
| Spike | 1.1.1-dev，RV32I 参考执行 |
| riscv-arch-test | `c8c813af55ea79acb97fe832c5737cc4539af3ee` |
| CoreMark | `f3e8f2e0941e42961aadcc52750b1b5577c157c9` |

上游来源为 CVW `addins` 的固定 checkout。构建逐字节核对测试源、环境头以及
CoreMark 六个受保护文件；SHA256 保存于归档 manifest。
上游 `coremark.md5` 早于该版本的后续修正，实际一致性依据固定 Git blob。

大型程序统一使用如下编译参数：

```text
-march=rv32i -mabi=ilp32 -O2 -ffreestanding -fno-builtin
-fno-pic -fno-pie -fno-stack-protector -fno-unwind-tables
-fno-delete-null-pointer-checks -ffunction-sections -fdata-sections
-msmall-data-limit=0 -mstrict-align -mno-relax
```

链接使用项目启动代码、固定链接布局、`-nostdlib -nostartfiles`、
`--gc-sections --no-relax` 及 RV32I/ilp32 libgcc。CoreMark 分别定义
`VALIDATION_RUN=0/1`；原 `core_main` 的 timed main 改名后被链接垃圾回收，
保留原始 `iterate`。指令审计逐条拒绝压缩指令、M、CSR 和其他不支持的编码。
关闭空指针检查删除是为了避免 GCC 在潜在空指针路径上生成 `ebreak`，不是改写算法。

### 3.2 生成配置

| 用途 | IROM 字数 | DTIM 字数 | 复位向量 |
| --- | ---: | ---: | --- |
| 教材程序 / 默认 RTL | 64 | 64 | `0x00000000` |
| 扩展配置程序 | 128 | 128 | `0x00000100` |
| RV32I 整机验收 | 128 | 64 | `0x00000000` |
| RV32I 扩展整机验收 | 256 | 128 | `0x00000100` |
| 架构测试和 CoreMark | 524288 | 32768 | `0x80000000` |

容量以 32 位字为单位。大型配置分别是 2 MiB IROM、128 KiB DTIM；较大 IROM
用于容纳 JAL 边界测试约 1.76 MB 的代码布局，没有修改默认生成参数。

大型程序的 DTIM 平台范围为 `0x80200000..0x8021ffff`，实际按容量低位索引；
`.data/.rodata` 从 `0x80210000` 开始，`.bss` 结束必须小于 `0x8021c000`，
栈顶为 `0x8021f000`。代码与数据在 Spike 地址空间内分离，在 DUT 中对应独立存储器。
`0x8021ff00` 为完成状态地址，`0x8021ff10` 为测量标记地址。

## 4. 验证方法与通过判据

### 4.1 三层结果检查

1. **模块/流水线检查：** Scala 测试直接检查有限输入、时钟边界、有效位、停顿、
   前递、冲刷和最终存储值。测试探针只存在于包装层，生产端口未增加调试字段。
2. **小程序事件检查：** 从历史 trace 提取全部有序访存请求，逐一比对地址、
   读写类型、Funct3、掩码及实际 store 数据；忽略无访存气泡，不忽略额外请求。
   加载数据正确性由后续运算/存储和固定签名检查，而非顶层直接导出 RAM 读值。
3. **大型软件签名检查：** 同一 ELF 在 Spike 上产生完整参考；DUT 从实际允许的
   M 级字节掩码写入重建签名，每个签名字节必须写入，最终逐字比较。

```mermaid
flowchart LR
    S[固定上游源码与平台适配] --> E[RV32I ELF]
    E --> R[Spike 执行]
    R --> G[完整参考签名]
    E --> I[IROM 镜像与启动初始化]
    I --> D[Verilator DUT 执行]
    D --> W[实际 M 级写入与测量标记]
    G --> C[逐字签名与 CRC 检查]
    W --> C
    C --> P[有界完成且全部一致]
```

大型程序通过普通 SW 初始化 `.data/.rodata`，清零 `.bss` 并建立栈。
初始化代码放在正文后；二次链接核对初始数据不因地址搬移而变化。
生产 RAM 仍保持无初始化契约，测试没有通过后门写入内部 RAM。

### 4.2 随机性和有界执行

RTL 仿真使用 `--x-initial unique --x-assign unique`、`randReset(2)`，随机种子
为 `1/17/2026`。大型程序每次在独立进程启动；小程序每个进程复位并完整重跑两次。
这些 seed 控制未初始化状态，不是三套重新生成的架构测试向量。

小程序 RTL 每次执行固定 300 周期，期间持续拒绝额外访存；定向测试为有限输入或
16–80 周期程序；架构程序上限 1,000,000 周期；CoreMark 每模式上限 30,000,000 周期。
Spike 子进程上限 120 秒，RTL 软件验证子进程上限 180 秒；超时/非零退出均传播。

大型程序实际向完成地址写 1 后，还必须通过完整签名检查；写 3 立即失败。
CoreMark 额外要求开始/结束标记顺序正确，测量区间大于零。
“程序跑到结束”本身不是通过条件。

## 5. 功能正确性结果

### 5.1 完整 Scala 回归

完整测试日志按测试类归组得到下表，合计 122 项，无失败、取消或跳过。

| 测试类 | 项数 | 主要检查对象 |
| --- | ---: | --- |
| PipelineHazardSpec | 7 | 前递优先级、W→D、x0、真实源依赖、停顿与重定向 |
| PipelineMemorySpec | 5 | 同步存储、掩码、PC 对齐、子字响应 |
| PipelineSmokeSpec | 6 | 填充、教材程序、控制转移、错误路径与复位 |
| CpuAcceptanceSpec | 2 | 两配置完整访存事件及固定字签名 |
| ExtendSpec | 13 | 立即数格式、位映射、边界和随机输入 |
| CmpSpec | 10 | 有符号/无符号比较与边界 |
| ShifterSpec | 10 | 移位功能、位宽及边界 |
| ALUSpec | 14 | 运算、标志、控制和模块接口 |
| RegFileSpec | 12 | 寄存器读写、x0、时钟及参数契约 |
| ControllerSpec | 5 | 编码合法性、控制、分支条件与组合端口 |
| SwByteMaskSpec | 8 | 尺寸、偏移、非法功能和对齐掩码 |
| SubwordWriteSpec | 12 | store 数据复制、功能和位宽 |
| SubwordReadSpec | 16 | 子字提取、符号/零扩展和边界 |
| CpuConfigSpec | 2 | 合法参数与非法宽度/容量/向量约束 |
| **合计** | **122** | **14 类，全部通过** |

逐类数据来自[完整 Scala 日志](reports/chapter7/evidence/scala-full.log)，而不是
可能残留旧包名或被后续 `testOnly` 覆盖的 JUnit 文件。

### 5.2 流水线特有行为

| 行为/风险 | 验证刺激和明确结果 | 证据 |
| --- | --- | --- |
| M/W 同时命中同一源 | 连续写 x1=1、x1=2，再使用 x1；结果为 5，选择 M | PipelineHazardSpec |
| W→E 与 W→D | 分别插入 1/2 个 NOP 后消费生产值；store 结果均为 13 | PipelineHazardSpec |
| x0 或无效写产生假前递 | 写 x0 后使用 x0；检查架构零、无效级和 M 级 load 不作为 ALU 前递 | PipelineHazardSpec |
| 假源字段造成多余停顿 | 立即数/U 型字段与 rd 重合、加载到 x0；检查无停顿 | PipelineHazardSpec |
| load-use/store→load | SW→LW→消费链；两类各一次停顿，F/D 的 PC/指令保持，E 注入气泡 | PipelineHazardSpec |
| 分支比较用旧值 | 使用前递值判断分支；两条年轻 store 均被冲刷 | PipelineSmokeSpec |
| load→branch | 恰好一次 load-use 停顿；错误路径 store 不出现 | PipelineSmokeSpec |
| JAL/JALR 链接或目标错位 | 链接分别为 4 和 24；目标 33 清最低位为 32 | PipelineSmokeSpec / misalign1-jalr |
| 重定向与停顿冲突 | 分别同时激活两种停顿原因与 E 重定向；重定向胜出并冲刷 D/E | Hazard 和 IFU 定向检查 |
| IROM 额外级或响应漂移 | 地址沿采样、禁用读保持、指令与 PCD/PC+4 对齐 | PipelineMemorySpec |
| DTIM 同步/共享地址错误 | 地址沿采样；写独占端口；字节更新及容量回绕 | PipelineMemorySpec |
| 子字控制错配 | 连续 LB/LBU/LH/LHU/LW；符号扩展、零扩展、不同偏移结果逐项一致 | PipelineMemorySpec |
| 复位产生残留写入 | M 有待存储且 W 有待写回时复位；无残留 store、valid 清零、旧 RF/RAM 保留 | PipelineSmokeSpec |

教材程序在 80 周期内严格只产生 `地址96=7`、`地址100=25` 两次 store。
整机 RTL 还检查中间的地址 96 读请求，故教材序列每次共 3 个访存事件。

### 5.3 多配置整机 RTL 验收

| 场景 | 每次预期访存事件 | 每进程执行 | 随机 seed | 结果 |
| --- | ---: | ---: | --- | --- |
| book | 3 | 2 次 × 300 周期 | 1、17、2026 | 全部通过 |
| expanded | 7 | 2 次 × 300 周期 | 1、17、2026 | 全部通过 |
| acceptance | 46 | 2 次 × 300 周期 | 1、17、2026 | 全部通过 |
| acceptance-expanded | 46 | 2 次 × 300 周期 | 1、17、2026 | 全部通过 |

合计 24 次执行、612 个有序访存事件比对。300 周期是检查窗口，不是程序的实测完成
时间；不能拿它作为基准运行周期。复位期间要求 MemRW、MemWrite 和 ByteMask 为零。
扩展配置检查非零向量和低地址容量回绕；Scala 两项 acceptance 额外核对固定签名。

### 5.4 §7.4 架构覆盖

全部执行固定上游版本的 `rv32i_m/I/src` 38 个文件，另执行教材表 7.10 的
`misalign1-jalr-01`。该文件现位于 `privilege/src`，本例只验证 JALR 清 bit 0，
不需要启用 trap 或 CSR 框架。

共 **39 个程序、12,490 个 inst_N 向量及 1 个 FENCE 场景**，每 seed 比对
**12,569 个 32 位签名字**（含 canary），三个 seed 共比较 **37,707 个签名字**。
所有 117 次仿真均一致。当前 ori 文件有 409 个向量，教材旧表列 560 个，
故当前总用例 12,491 与教材 2022 年总数 12,642 不同；没有据旧数量虚报覆盖。

原始向量和测试宏保持完整。平台关闭环境头中的压缩对齐填充
（`.option rvc`→`.option norvc`），不启用无关 trap/instret 框架。
这些平台适配与同一 ELF 的 Spike 参考一起验证。
39 个程序的完整统计见附录 A。

## 6. CoreMark 功能正确性

### 6.1 执行条件

单上下文、2000 字节静态工作数据、10 iterations，执行全部 list/matrix/state
算法。性能种子 `seed1=seed2=0`，验证种子 `seed1=seed2=0x3415`，
`seed3=0x66`。功能驱动按上游 `core_main` 初始化，调用未修改的上游 `iterate`。
软件乘除及访存都由 DUT 执行，未使用宿主机替代算法计算。

| 模式 | seed CRC | list CRC | matrix CRC | state CRC | final CRC | errors | iterations |
| --- | --- | --- | --- | --- | --- | ---: | ---: |
| performance | e9f5 | e714 | 1fd7 | 8e3a | fcaf | 0 | 10 |
| validation | 18f2 | e3c1 | 0747 | 8d84 | c64e | 0 | 10 |

前四个 CRC 同时匹配上游 2K 模式的固定参考；final CRC 匹配 Spike 对同一 ELF
的独立执行。签名共 7 个 32 位字，包括错误数和迭代数；每种模式三个 seed 全部一致。
CRC 具有碰撞可能，CoreMark 用于综合软件执行验证，并与逐字架构签名及定向测试
共同构成证据，不替代完整执行轨迹的等价证明。

## 7. 性能测量与定量结果

### 7.1 周期口径

`SignatureRtlTest.cpp` 每个 tick 对应一个 DUT 上升沿及一个下降沿，计为一个时钟
周期。周期值是 RTL 模型计数，宿主机运行秒数不是 DUT 执行时间。

测量区间始于 M 级向 `0x8021ff10` 实际 SW 值 1 的观察周期，止于向同地址
实际 SW 值 2 的观察周期，两者差为 C。两个标记具有相同的 M 级观察位置，
相减不会引入不同阶段的基准差。区间包括标记之间的函数调用和指令执行开销；
不包含数据初始化及区间之外的 CRC 检查。

完成周期 T 是复位释放后到成功完成 store 的有界循环计数，包含该 store 所在
检查循环的 tick；不包含启动前的两个复位 tick。它比测量区间更长，但 T−C
不能进一步拆分为精确启动、校验、标记等各项开销。

平均周期/迭代 = C / 10。这里只是平均值，没有分别计时每次迭代，也没有测量
退休指令数，因此不报告 CPI/IPC。

### 7.2 实测周期

| 模式 | 总完成 T | 执行区间 C | 平均周期/迭代 | 区间之外 T−C | 占总周期 |
| --- | ---: | ---: | ---: | ---: | ---: |
| performance | 10,558,861 | 10,533,211 | 1,053,321.1 | 25,650 | 0.242924% |
| validation | 11,357,303 | 11,331,447 | 1,133,144.7 | 25,856 | 0.227660% |

验证种子的执行周期比性能种子增加 **7.578278%**，差值为 **798,236 周期**。
这是输入种子影响算法路径的结果，不能作为硬件改动的加速或减速结论。
启动和测量区间外检查占总周期约 0.24%，主要时间位于真正的迭代计算区间。

### 7.3 重复性

| 模式 | 三个 seed 的 C | 最小/最大 | 总体标准差 |
| --- | --- | --- | ---: |
| performance | 10,533,211 / 10,533,211 / 10,533,211 | 相同 | 0 周期 |
| validation | 11,331,447 / 11,331,447 / 11,331,447 | 相同 | 0 周期 |

重复性表明这些执行没有因所测试的未初始化状态而产生可见结果或周期变化。
三个 seed 不是统计总体采样，零差异不能证明对所有初态都独立，也不代表零测量误差。

### 7.4 归一化吞吐与频率换算

用于本项目内部比较的迭代吞吐定义为：

```text
迭代吞吐（iterations/Mcycle）= 10 × 1,000,000 / C
10 次迭代时间（ms）= C / (1000 × F_MHz)
```

| 模式 | iterations/Mcycle | 假定 50 MHz | 假定 100 MHz | 假定 200 MHz |
| --- | ---: | ---: | ---: | ---: |
| performance | 0.949378 | 210.66422 ms | 105.33211 ms | 52.666055 ms |
| validation | 0.882500 | 226.62894 ms | 113.31447 ms | 56.657235 ms |

时间列为按假定频率的算术换算，不是测得的运行时间或频率承诺。
尚未综合/布局布线，不能确认任一所列频率可实现。
该吞吐不标为官方 CoreMark/MHz：使用固定迭代功能驱动，没有运行上游 timed main
的校准和输出，也没有满足真实计时及至少 10 秒的计分条件。

### 7.5 程序容量与架构程序周期

| CoreMark 模式 | 实际 code.bin | 初始 .data | .bss | .data 起点至 .bss 末尾跨度 |
| --- | ---: | ---: | ---: | ---: |
| performance | 11,660 B | 788 B | 2,104 B | 2,904 B |
| validation | 11,676 B | 796 B | 2,096 B | 2,896 B |

上述是链接阶段的文件/静态分配大小，含启动及软件辅助函数；工作数据已经包含
在静态分配中，不再额外累加 2000 B。未测量栈峰值、RAM 动态高水位或综合后的资源。
测试配置 2 MiB 的 IROM 容量不能当作 CoreMark 代码体积。

39 个架构程序每 seed 的总完成周期为 **222,132**，中位数 **3,939**，范围为
**2,207–14,782**。最长程序 bgeu-01 只使用其 1,000,000 周期上限的 1.4782%。
这些周期包含初始化、测试宏和签名写入；各文件向量数不同，不能据此排列各指令
本身的速度，也不能把静态 instructionWords 作为动态退休指令数计算 CPI。

## 8. 流水线性能解释与结论边界

已验证的微架构机制可以解释部分执行代价，但没有采集 CoreMark 全程的事件计数：

| 机制 | 已知局部行为 | 全程序尚缺数据 |
| --- | --- | --- |
| ALU 前递 | 连续依赖可由 M/W 前递及 W→D 旁路解决 | 前递命中次数和分布 |
| load-use | 紧邻真实源依赖产生一个停顿 | CoreMark 中该类停顿总数 |
| DTIM 共享地址 | E 中 store 与 D 中 load 相邻时产生一个结构停顿，检测不依赖地址是否相同 | 该类冲突占比 |
| E 级控制转移 | 重定向冲刷 D/E 的年轻指令；有定向结果验证 | 动态 taken 比例、重定向数量和损失周期 |
| 流水线填充 | ADDI→SW 定向序列首次 store 在零基周期索引 4 观察到 | 全程序填充/排空独立统计 |

在理想独立指令流、无停顿或重定向时，流水级可每周期推进一条指令，这是结构
层面的吞吐预期。本次没有以退休计数实测理想 IPC=1，也没有从总周期反推出各类
冒险占比。静态存在五级流水线并不意味着软件加速五倍。

单周期基线保存在 `76ebb79`，但本次未在同一 CoreMark 软件与同等时钟条件下做
对照测量。若比较实际时间，必须同时知道单周期与流水线的可实现频率、执行周期
和存储器延迟；目前没有足够证据给出加速比。

## 9. 检查器可靠性与审查闭环

### 9.1 主动失败条件

| 负向场景 | 原脚本/检查器行为 | 最终行为 |
| --- | --- | --- |
| 参考首个签名字翻转一位 | 正常程序无法匹配篡改参考 | 非零退出，报告 Signature word 0 differs |
| 周期上限设为 1 | 程序不能完成 | 非零退出，报告 Cycle limit reached |
| 删除一个上游架构源 | 原来遍历现有文件，可静默减少覆盖 | 从固定 Git tree 取清单，缺失立即拒绝 |
| 删除环境头但保留旧构建拷贝 | 原来可能继续使用旧内容 | 核对固定清单及真实来源，缺失拒绝 |
| 一个有效测试名加一个错误名 | 原来忽略错误名称 | Unknown architecture tests，拒绝 |
| 全部来源完整，选择附加 JALR | 原来没有附加用例 | 完整清单及单独选择均可执行 |

四项源清单回归先在原脚本上全部失败，再在修正后全部通过；最终合并入口包括
这四项检查。归档保留[修正前日志](reports/chapter7/evidence/inventory-before-fix.log)
及[修正后完整运行日志](reports/chapter7/evidence/validation-all.log)。

只读分支审查没有发现 Critical；发现的一项 Important 为源清单可能静默缩减。
该项在 `8c51597` 修复，回归由失败转为通过，再完成全部 41 程序 × 3 seed 验证。
这是一次审查及作者修复验证，没有把修复后的结果表述为再次独立审查。

## 10. 复现步骤

在工程目录配置固定版本 CVW addins、GNU RV32I 工具链、Spike、Verilator 和原
Chisel 工具链，然后执行：

```bash
make test SBT=./scripts/sbt-local.sh
make test-acceptance SBT=./scripts/sbt-local.sh
make test-rtl SBT=./scripts/sbt-local.sh
make test-validation SBT=./scripts/sbt-local.sh
```

可以单独执行 `make test-arch`、`make test-coremark`、`make test-validation-builder`。
`CVW_ROOT` 默认 `/home/unlastingstar/cvw`；可用 `RISCV_TOOL_PREFIX`、`SPIKE`、
`VERILATOR` 指定工具，`CHISEL_TOOLCHAIN_DIR` 指定 Chisel 工具链。
实际重新验证时保持默认 `VALIDATION_SEEDS='1 17 2026'`。

大型软件默认重新构建 ELF、生成 Spike 签名、生成 RTL、编译并运行 Verilator。
每个程序镜像由独立进程重新装入固定 IROM 文件名，故不能并行运行多个大型软件
测试入口或在仿真期间同时修改该镜像。失败/超时必须作为验证失败处理。

仅复核本报告归档数据和计算，无需仿真工具：

```bash
python3 docs/reports/chapter7/recompute_metrics.py
```

此命令核对 123 条结果、39 架构程序、41 份参考签名、种子覆盖、日志统计和边界，
重新生成 `results.csv` 与 `metrics.json`。它审计已有证据，不产生新的 DUT 测试结果。

## 11. 未验证项目与后续测评条件

| 待测项目 | 当前缺口 | 要获得可报告结果所需工作 |
| --- | --- | --- |
| CPI/IPC 与动态指令分布 | 没有退休指令计数 | 在测试层采集退休或等价执行轨迹，定义计数区间 |
| 冒险与分支代价分解 | 没有全程事件统计 | 统计两类停顿、重定向及重叠事件，避免重复计数 |
| 单周期对照加速比 | 没有同软件、同计时条件的对照 | 适配同步存储器条件并测双方周期/频率 |
| 官方 CoreMark 得分 | 固定迭代功能驱动，无真实时间源 | 保留算法、采用正式计时入口、满足计分要求 |
| 最高频率与资源 | 未综合或物理实现 | 给定目标工艺/FPGA、约束、RAM 映射及实现流程 |
| 功耗、能效、面积 | 无对应实现和活动数据 | 实现后测量或有约束的估算 |
| 全 ISA/特权认证 | 超出现有核心范围 | 实现异常、CSR 等必需能力，再执行对应测试体系 |
| 形式化与穷尽覆盖 | 当前为有限仿真 | 引入性质证明或更广的随机差分验证 |

本报告覆盖当前工程已完成的功能验证和周期性能测量；上述未测项目保留为明确边界，
不以推测数字补齐。后续工作不属于本次报告编写已执行的内容。

## 12. 证据归档与来源索引

| 文件 | 用途 |
| --- | --- |
| [validation-results.json](reports/chapter7/evidence/validation-results.json) | 最终 41 程序的配置、版本、CRC、每 seed 原始结果 |
| [artifact-manifest.json](reports/chapter7/evidence/artifact-manifest.json) | 工程来源 SHA256、ELF/镜像/参考哈希及静态容量 |
| [results.csv](reports/chapter7/results.csv) | 全部 123 次执行的表格数据 |
| [metrics.json](reports/chapter7/metrics.json) | 从原始结果计算的性能及统计量 |
| [scala-full.log](reports/chapter7/evidence/scala-full.log) | 完整 122 项 Scala 运行日志 |
| [scala-acceptance.log](reports/chapter7/evidence/scala-acceptance.log) | 单独 8 项验收入口日志 |
| [rtl-acceptance.log](reports/chapter7/evidence/rtl-acceptance.log) | 4 配置、12 进程、24 次复位执行日志 |
| [validation-all.log](reports/chapter7/evidence/validation-all.log) | 源清单检查、Spike、RTL 生成、123 次执行及负向检查 |
| `reports/chapter7/evidence/*.signature` | 41 份完整 Spike 参考签名，按程序命名 |
| [recompute_metrics.py](reports/chapter7/recompute_metrics.py) | 使用 Python 标准库复核归档并重算派生指标 |

ELF、代码镜像和生成 RTL 的大文件未随报告归档；可以通过复现入口重新生成，
并用 manifest 的哈希核对软件产物。归档的 PASS 日志并不是全指令退休轨迹，
因此不据此声明逐条执行等价。

方法对应的工程源：

- [Hazard](../src/main/scala/riscvpipeline/hazard/Hazard.scala)、
  [流水线冒险测试](../src/test/scala/riscvpipeline/PipelineHazardSpec.scala)、
  [同步存储测试](../src/test/scala/riscvpipeline/PipelineMemorySpec.scala)、
  [smoke 测试](../src/test/scala/riscvpipeline/PipelineSmokeSpec.scala)。
- [整机 Scala 验收](../src/test/scala/riscvpipeline/CpuAcceptanceSpec.scala)、
  [整机 RTL 检查器](../src/test/cpp/PipelineRtlTest.cpp)。
- [软件构建](../scripts/build-validation.py)、[软件验证调度](../scripts/run-validation.py)、
  [签名及周期检查器](../src/test/cpp/SignatureRtlTest.cpp)。
- [CoreMark 功能驱动](../programs/coremark/benchmark.c)、
  [测量标记移植层](../programs/coremark/core_portme.c)。

相关提交：单周期基线 `76ebb79`；流水线核心 `2807a30`；同步 DTIM `1effa22`；
整机验收适配 `b36a525`；架构/CoreMark 平台 `b57a684`；清单完整性及教材附加
用例 `8c51597`；验收记录 `9847509`。原实施记录和操作说明分别见
[流水线实施记录](chapter7-pipeline.md)、[完整验收记录](chapter7-validation.md)。

## 附录 A：全部架构程序结果

下表周期为 seed 1 的完整程序完成周期；seed 17、2026 完全相同。
FENCE 的 inst_N 为 0，表示手写场景，实际场景数为 1。

| 程序 | inst_N 向量 | 签名字/seed | 完成周期 | seed 1/17/2026 |
| --- | ---: | ---: | ---: | --- |
| add-01 | 588 | 590 | 7,122 | PASS / PASS / PASS |
| addi-01 | 561 | 563 | 5,968 | PASS / PASS / PASS |
| and-01 | 584 | 586 | 7,075 | PASS / PASS / PASS |
| andi-01 | 562 | 564 | 5,975 | PASS / PASS / PASS |
| auipc-01 | 64 | 66 | 2,716 | PASS / PASS / PASS |
| beq-01 | 583 | 585 | 11,787 | PASS / PASS / PASS |
| bge-01 | 590 | 592 | 12,457 | PASS / PASS / PASS |
| bgeu-01 | 726 | 728 | 14,782 | PASS / PASS / PASS |
| blt-01 | 580 | 582 | 12,231 | PASS / PASS / PASS |
| bltu-01 | 726 | 728 | 14,726 | PASS / PASS / PASS |
| bne-01 | 584 | 586 | 12,869 | PASS / PASS / PASS |
| fence-01 | 0 | 3 | 2,207 | PASS / PASS / PASS |
| jal-01 | 32 | 34 | 3,939 | PASS / PASS / PASS |
| jalr-01 | 33 | 35 | 3,363 | PASS / PASS / PASS |
| lb-align-01 | 33 | 35 | 2,820 | PASS / PASS / PASS |
| lbu-align-01 | 32 | 34 | 2,801 | PASS / PASS / PASS |
| lh-align-01 | 32 | 34 | 2,801 | PASS / PASS / PASS |
| lhu-align-01 | 32 | 34 | 2,801 | PASS / PASS / PASS |
| lui-01 | 63 | 65 | 2,518 | PASS / PASS / PASS |
| lw-align-01 | 32 | 34 | 2,801 | PASS / PASS / PASS |
| or-01 | 587 | 589 | 7,118 | PASS / PASS / PASS |
| ori-01 | 409 | 411 | 4,971 | PASS / PASS / PASS |
| sb-align-01 | 70 | 72 | 2,925 | PASS / PASS / PASS |
| sh-align-01 | 71 | 73 | 2,932 | PASS / PASS / PASS |
| sll-01 | 89 | 91 | 2,865 | PASS / PASS / PASS |
| slli-01 | 88 | 90 | 2,768 | PASS / PASS / PASS |
| slt-01 | 584 | 586 | 7,096 | PASS / PASS / PASS |
| slti-01 | 560 | 562 | 5,958 | PASS / PASS / PASS |
| sltiu-01 | 699 | 701 | 6,859 | PASS / PASS / PASS |
| sltu-01 | 722 | 724 | 8,188 | PASS / PASS / PASS |
| sra-01 | 90 | 92 | 2,870 | PASS / PASS / PASS |
| srai-01 | 87 | 89 | 2,760 | PASS / PASS / PASS |
| srl-01 | 92 | 94 | 2,897 | PASS / PASS / PASS |
| srli-01 | 90 | 92 | 2,778 | PASS / PASS / PASS |
| sub-01 | 592 | 594 | 7,145 | PASS / PASS / PASS |
| sw-align-01 | 68 | 70 | 2,902 | PASS / PASS / PASS |
| xor-01 | 588 | 590 | 7,117 | PASS / PASS / PASS |
| xori-01 | 566 | 568 | 5,997 | PASS / PASS / PASS |
| misalign1-jalr-01 | 1 | 3 | 2,227 | PASS / PASS / PASS |
| **合计** | **12,490 + 1 FENCE 场景** | **12,569** | **222,132** | **117 次执行全部通过** |
