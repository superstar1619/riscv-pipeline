# 11：EndianSwap——RV32 数据字节交换

`riscvpipeline.privileged.EndianSwap` 是固定 32 位的纯组合 `RawModule`。
`BigEndianM=0` 时原样输出，设为 1 时颠倒四个字节的排列，保留每个字节内部的位顺序。
模块无时钟、复位、寄存器或锁存器，输入变化在组合传播后立即反映到输出。

## 教材与 CVW 依据

- 教材 §8.4.7，书页 378–380（PDF 页 413–415）：数据端序及读写通路的字节交换。
- 本地 CVW revision `2f82f001139358be7646e910ff6fa2a140bed620` 的
  `src/lsu/endianswap.sv`：按整字节映射，BigEndianM 为零时旁路输入。
- [CSRStatus 说明](03-csr-status.md)：UBE/SBE/MBE、实际模式与 MPRV 对 BigEndianM 的选择。

本模块按第八章 RV32 路线固定为 32 位，没有加入 CVW 的 64/128 位扩展。
放在 privileged 包是本工程本阶段的模块组织选择；它处理的是数据，仍由 LSU 在读写
数据通路调用。整数 ALU 运算和指令取指不因本模块改变。

## 完整接口与字节映射

| 方向 | 端口 | 位宽 | 含义 |
| --- | --- | --- | --- |
| 入 | `io.BigEndianM` | Bool | 当前数据访问使用大端，由 CSRStatus 选择 |
| 入 | `io.a` | UInt32 | 需要转换的完整数据字 |
| 出 | `io.y` | UInt32 | 选择旁路或交换后的完整数据字 |

通用数据端口保留 CVW 的小写 `a/y`。没有时钟或 reset 端口，也没有模式、地址、
Funct3、字节掩码或有效信号；所有输入组合均有确定的输出。

| BigEndianM | 输出高字节到低字节 |
| --- | --- |
| 0 | `a[31:24]`, `a[23:16]`, `a[15:8]`, `a[7:0]` |
| 1 | `a[7:0]`, `a[15:8]`, `a[23:16]`, `a[31:24]` |

例如 `a=0x01234567` 时，旁路结果为 `0x01234567`，交换结果为 `0x67452301`。
`a=0x80ff7f01` 交换后为 `0x017fff80`：最高字节的位型 `0x80` 保持为 `0x80`，
只移动到最低字节位置，没有变为 `0x01`。连续交换两次恢复原输入。

## 地址、子字和模式边界

假设内存物理地址 A、A+1、A+2、A+3 的字节为 `01 7f ff 80`，原生小端读取整字
得到 `0x80ff7f01`。大端整字加载先经交换得到 `0x017fff80`；随后 LSU 必须按访问宽度
调整子字选择偏移，不能将原小端偏移直接用于交换后的字。

本路线的大端子字选择是：字节偏移 XOR 3，半字偏移 XOR 2，整字偏移保持为 0。
因此地址 A 的 LBU 应取 `0x01`，地址 A 的 LHU 应取 `0x017f`。如果只交换整字而不调整
偏移，这些子字会选错。自然对齐与地址范围异常仍由 LSU/Trap 负责。

写数据先按 SubwordWrite 复制所需字节/半字，再交换重复字；物理写掩码仍根据实际地址
选择字节通道。例如大端在 A 存半字 `0x1234`：重复字 `0x12341234` 交换为
`0x34123412`，低两通道写掩码使 A/A+1 分别写入 `12/34`。SB 的重复字节交换前后相同。
这里不改变掩码，也不在此叶模块额外实现这些 LSU 步骤。

`BigEndianM` 必须对应实际数据访问的权限环境：MPRV 只在规定的 M 模式数据访问中
借用 MPP 环境，S/U 和各端序位的选择由 CSRStatus 统一处理。此模块不保存模式状态，
所以模式切换后输入选择改变就立即使用新字节映射；上层仍需保证年轻访问不会使用旧环境。

## 测试、生成与实际结果

```bash
./scripts/sbt-local.sh 'testOnly riscvpipeline.privileged.EndianSwapSpec'
./scripts/sbt-local.sh \
  'runMain riscvpipeline.GenerateEndianSwap target/chapter8/completion/endian/rtl'
verilator --lint-only --top-module EndianSwap \
  target/chapter8/completion/endian/rtl/EndianSwap.v
```

`GenerateEndianSwap [targetDir]` 默认输出到 `generated/endian-swap`。
测试先运行固定旁路的行为骨架，6 项中 5 项按未交换行为失败。6 项覆盖手工整字结果、
全部 32 个 one-hot 位及字节内位位置、无时钟沿的立即更新、1024 个固定种子全宽随机字、
128 个二次交换恢复，以及实际 RTL 的三个端口与无状态属性。
随机参考模型用 Java ByteBuffer 以大端编码输入，再以小端解码，独立于生产 Cat 表达式。

2026-10-08 的实际结果如下；日志目录为 `target/chapter8/completion/endian/`。

| 检查 | 实际结果 |
| --- | --- |
| TDD 行为骨架 | 6 项中 5 项按未交换字节失败；`red-behavior.log` |
| 实现与测试编译、独立仿真 | 6/6 通过，含 1024 随机字及 128 次二次交换；`green.log` |
| RTL 生成 | 成功；`generate.log`、`rtl/EndianSwap.v` |
| 实际端口与无状态属性 | 仅 BigEndianM/a/y，宽度 1/32/32；没有时钟、复位、always 或 reg |
| Verilator lint | 退出码 0，无警告或错误；`lint.log`，Verilator 5.036 |
| 新增文件格式与链接 | Scala 95 列、无行尾空白，本页本地链接均存在 |

沿用既有 ChiselStage 弃用提示，没有运行全套 Scala 或大型 CoreMark；统一回归由
第十四、十五阶段负责。
这些结果针对整字交换叶模块；子字地址、写掩码、CSR 模式切换和实际内存访问由流水线
集成验收覆盖。模块没有扩展到 RV64/RV128、向量、MMU、页表访问或指令端序转换。
