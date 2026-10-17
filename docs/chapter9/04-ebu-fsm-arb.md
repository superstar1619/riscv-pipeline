# EBUFSMArb

CVW `src/ebu/ebufsmarb.sv` 的 LSU 优先双状态仲裁适配，保留同名端口、版权与许可证。
仅支持单次 NONSEQ，因此删除 burst beat 计数与 FinalBeat 寄存器；HBURST 端口保留。
IDLE 同时请求时保存 IFU 并选择 LSU，ARBITRATE 等到 HREADY 且不再同时请求时
重放 IFU。IFUReqDelay 在重放后抑制 LSU ready，防止流水线仍保持原访存请求时重复接受。
显式 clk/reset，状态与延迟请求同步复位；组合输出不增加 reset 门控。

2026-10-17：先编写测试并确认缺失 EBUFSMArb 类型导致预期失败；实现后
EBUFSMArbSpec 3/3 通过：独立请求、冲突保存及可变等待后恢复、持续冲突保持仲裁。
联合三个叶模块共 12/12 测试通过。测试入口 `testOnly riscvpipeline.ebu.EBUFSMArbSpec`，
统一经 `flock target/chapter9-sbt.lock env JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx3g'
./scripts/sbt-local.sh` 运行。

日志及 RTL：`target/chapter9/modules/ebu-fsm-arb/green.log`、`rtl/EBUFSMArb.v`。
GenerateEBUFSMArb 成功；`verilator --lint-only -Wno-fatal --top-module EBUFSMArb`
成功，无 RTL 警告。仅叶模块仿真不证明两个 BusFSM 的数据归属，交由 EBU 集成测试验证。
