# EBU

合并 RV32 IFU/LSU 的非突发 AHB 请求。接口沿用 CVW `src/ebu/ebu.sv` 的平坦名称，
外侧地址 34 位、数据 32 位，另提供共享 HRDATA 输入与每方 IFUHRDATA/LSUHRDATA 输出。
源码保留 CVW 版权和许可证。ControllerInput 保存冲突时 IFU 的全部地址字段；
EBUFSMArb 优先发出 LSU 地址，随后在 LSU Data 完成时重放 IFU 地址。
HBURST=0、HMASTLOCK=0；HPROT 指令=2、数据=3，HRESP 当前仅支持 OKAY。

相较 CVW 增加同步 Pending 与 DataOwner 寄存器。Pending 区分每方尚待接受的 Adr 与
已虚拟接受的请求，DataOwner 只在外部 HREADY 的时钟沿记录真实接受地址的归属。
尚待接受的 Adr 可以获得 ready；已在 Data 的一方只能获得自己 Data 相位的完成响应。
冲突时 IFU 先虚拟进入 Data，直到保存地址重放后的响应才完成。这样既能重放原 IFU
地址，也不会把 LSU 响应提前捕获到 IFU。归属寄存器切断 HTRANS 与 ready 的组合反馈。
读数据可广播，捕获由每方 ready 控制；写数据和掩码来自 LSU 的已接受载荷缓冲。

采用显式 clk/reset 与同步复位；组合控制不增加 `&& !reset`。HCLK/HRESETn 仅为
CVW 兼容输出。BusFSM 的 Mem3 由上层 Stall 保持，完成期间既不丢响应也不重发。

2026-10-17：先编写 EBUSpec，确认生产 EBU 类型缺失导致预期失败。实现后 9/9 测试通过：

- 0/1/3/7 拍等待分别测试 LSU 读/写与同时 IFU 请求，共八项；记录全部外部地址接受，
  要求准确为 LSU、原 IFU 各一次，检查 34 位地址、HPROT、SINGLE、无锁及写字节掩码。
- LSU 完成前 IFU 缓冲不变，IFU 完成后捕获自己的数据；Stall/Flush 不丢已接受事务，
  完成后再等待八拍无重复。上游修改实时 IFU 地址仍重放原保存地址。
- IFU 先开始，在其 Data 完成同拍接受后来的 LSU 地址，两个读结果保持归属。

测试及生成统一通过 `flock target/chapter9-sbt.lock env
JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx3g' ./scripts/sbt-local.sh`；为避免并行
开发中其他模块未完成测试的编译干扰，本次命令临时设置 Test/sources 仅包含 ebu 与
ProjectTest，再执行 `testOnly riscvpipeline.ebu.EBUSpec`，没有修改 build.sbt。
GenerateEBU 成功；`verilator --lint-only -Wno-fatal --top-module EBU` 成功且无 RTL 警告。
证据目录为 `target/chapter9/modules/ebu/`，包含 red.log、green.log、lint.log 与 rtl/EBU.v。

范围：此测试集验证模块协议，整机程序、异常/中断及综合时序由后续集成验收；
不支持 burst、atomic、AHB ERROR 或 RV64。
