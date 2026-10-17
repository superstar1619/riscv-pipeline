# ControllerInput

CVW `src/ebu/controllerinput.sv` 的地址相位输入保存/重放，保留同名平坦端口、版权与
许可证。地址 34 位，保存 HWRITE、HSIZE、HBURST、HTRANS、HADDR 全部字段；
saveEnabled=false 为 LSU 直接旁路。Request 来自选择后的 HTRANS，Disable 只抑制
HREADYOut，不修改载荷或 Request。显式 clk/reset，同步复位，无组合 reset 门控。

2026-10-17：先编写测试并确认缺失 ControllerInput 类型导致预期失败，生产实现后
ControllerInputSpec 4/4 通过：完整保存与恢复、保存禁用旁路、两种参数的 ready 真值表。
联合三个叶模块共 12/12 测试通过。

测试入口 `testOnly riscvpipeline.ebu.ControllerInputSpec`，统一经
`flock target/chapter9-sbt.lock env JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx3g'
./scripts/sbt-local.sh` 执行。日志 `target/chapter9/modules/controller-input/green.log`。
GenerateControllerInput 生成 `rtl/ControllerInput.v`；
`verilator --lint-only -Wno-fatal --top-module ControllerInput` 成功，无 RTL 警告。

HBURST 仅作为保存字段保留；系统不使用 burst，不承诺支持 burst 仲裁。
