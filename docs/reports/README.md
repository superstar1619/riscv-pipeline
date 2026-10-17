# 运行报告导航

本页为手写固定导航。运行过程只在 `target/` 写入报告和动态索引，不更新 `docs/`。

[打开运行报告索引](../../target/reports/index.md)。索引按类型列出运行 ID、实际状态、报告、证据和复现命令，
并分别显示最近运行与最近通过。最近一次失败也会保留；状态无法确认的旧资料标为历史资料。
定位指针和历史通过结论不能替代新运行的逐例证据。

## 统一验证

```bash
make report-verification SBT=./scripts/sbt-local.sh
```

运行位于 [target/verification/runs](../../target/verification/runs/)，支持 `--output` 指定独立输出目录。
报告与证据放在同一次运行目录，索引直接链接过去。

<a id="chapter8-testplan"></a>
## 第八章详细测试

```bash
make report-chapter8 SBT=./scripts/sbt-local.sh
```

运行位于 [target/chapter8-testplan/runs](../../target/chapter8-testplan/runs/)。
逐例报告在本轮检查通过后生成，保留原有新鲜性、矩阵、签名和负向检查门控。

## 第七、八章联合验证

```bash
make report-chapter7-8-cvw SBT=./scripts/sbt-local.sh
```

新运行位于 [target/chapter7-8-cvw/runs](../../target/chapter7-8-cvw/runs/)，失败结果同样保留。
迁移前的联合证据仍在 [历史证据目录](../../target/reports/chapter7-8-cvw/)。

## 第九章模块实验

首模块 [BusFSM 当前运行报告](../../target/chapter9-testplan/runs/20261016T094039485551Z-bus-fsm-sync-reset/report.md)
保存 19 项测试、两配置 RTL lint 和原始证据；长期说明见
[BusFSM 实验报告](../chapter9/01-bus-fsm.md)。这是独立模块结果，第九章整机验收尚未执行。
本次按用户要求移除组合 reset 门控；[首版历史证据](../../target/chapter9-testplan/runs/20261016T090912069968Z-bus-fsm/report.md)保留。
复验入口为 `make test-bus-fsm SBT=./scripts/sbt-local.sh`，会更新测试产物，
不会覆盖或重新生成这份已归档运行报告。

## 导入的历史资料

- [原 docs 自动报告及迁移清单](../../target/reports/imported-docs/2026-10-14/report.md)。
- [单周期运行证据、生成 RTL 与波形](../../target/reports/single-cycle/2026-10-14/report.md)。
- [单周期长期说明](../chapter7/single-cycle/README.md)和[独立源码快照](../../references/single-cycle/README.md)。

## 清理后如何恢复入口

`make clean` 会删除生成报告、原始证据、动态索引和导入的运行产物；本页和源码快照保留。
首次检出仓库或清理后，target 链接暂时没有目标，这是可清理产物的预期状态。
重新执行上方对应报告命令即可创建新的运行和索引。
单周期历史测试可按[快照复现说明](../../references/single-cycle/README.md)重新执行；
已清理的历史证据不会被新运行冒充或恢复。
