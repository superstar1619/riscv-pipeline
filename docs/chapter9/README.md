# 第九章：总线接口实验

依据教材第九章，代码参考本地 CVW `src/ebu`、`src/uncore` 及流水线相关模块。
从 [完整实验报告](20-lab-report.md)开始阅读；范围和检查点见
[已确认的实施计划](plans/2026-10-16-implementation.md)。

**2026-10-17 用户授权连续完成全部模块：每模块验证后单独 git commit，最后统一检查。**
此前的逐模块等待要求由这条新指令替代。

## 当前进度

17个模块检查点均已完成实现、定向测试、RTL 生成和 lint，分别提交到
`chapter9-bus-interface`。新增总线模块只使用同步寄存器复位，没有增加请求与
`!reset` 的组合门控。最终144/144条独立 RTL 整机矩阵通过；完整 Scala 回归688/688通过，随后 APB
子字地址修复的18项关联复验通过；Python105/105通过。详见完整实验报告。
本章只验收当前 RV32 项目；RV64GC、浮点程序与缓存突发原始测试列为未覆盖。

|模块报告|定向测试|实现提交|
|---|---:|---|
|[01 BusFSM](01-bus-fsm.md)|19/19|ccdc79c|
|[02 AHBInterface](02-ahb-interface.md)|5/5|10b975a|
|[03 ControllerInput](03-controller-input.md)|4/4|a288eb0|
|[04 EBUFSMArb](04-ebu-fsm-arb.md)|3/3|2e40dad|
|[05 EBU](05-ebu.md)|9/9|c9346ba|
|[06 AdrDec](06-adr-dec.md)|2/2|54efb91|
|[07 AdrDecs](07-adr-decs.md)|1/1|7c48404|
|[08 RAMAHB](08-ram-ahb.md)|6/6|fc64563|
|[09 ROMAHB](09-rom-ahb.md)|3/3|f61a8fc|
|[10 AHBAPBBridge](10-ahb-apb-bridge.md)|3/3|fc9fbc2|
|[11 APBRegisterBank](11-apb-register-bank.md)|7/7|0163047、e5fcccd|
|[12 Uncore](12-uncore.md)|7/7|6f38f78、e5fcccd|
|[13 BusLSU](13-bus-lsu.md)|5/5|601c896|
|[14 BusIFU](14-bus-ifu.md)|7/7|ab91842、787dc64|
|[15 BusHazardControl](15-bus-hazard.md)|3/3|8251b08|
|[16 BusPipeline](16-bus-pipeline.md)|3/3|3690399|
|[17 RiscvPipelineSoC](17-soc.md)|4/4，两次复位|66f0190|

定向用例合计91项（含独立 RTL 暴露 APB 子字地址问题后新增的6项回归）。[整机 Test Plan](18-testplan.md)说明程序预期与144条必要记录；
[独立审阅和旧路径回归](19-review.md)记录代码复核、Python检查和第七章RTL结果。

```bash
make test-chapter9 SBT=./scripts/sbt-local.sh
make test-chapter9-rtl SBT=./scripts/sbt-local.sh
make generate-bus-soc SBT=./scripts/sbt-local.sh
```

## 目录约定

本目录保存手写设计、模块实验说明与检查进度。自动报告、原始日志、RTL、波形和
源码哈希保存在 `target/chapter9-testplan/runs/`，不覆盖旧运行。
`make clean` 会删除这些运行产物；长期文档与源码保留。
