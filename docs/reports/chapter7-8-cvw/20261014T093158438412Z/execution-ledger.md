> 历史证据已迁至 [target 中的证据目录](../../../../target/reports/chapter7-8-cvw/20261014T093158438412Z/)。`make clean` 会删除原始证据，Markdown 报告保留；重新运行相应报告命令将产生新的一次运行。

# 第七、八章 CVW 执行记录
用户本轮提供的中文计划为唯一需求来源。CPU 实现禁止修改；上游 ~/cvw 只读。
初始仓库 HEAD: 0211f94774b10caca3a6030e27994e9eb2de5981，原始工作区干净。
任务：1 结果收集与负向检查；2 第八章原样例适配；3 联合运行；4 中文报告与证据归档。
Ruling: 在现有仓库内定点编辑，不建立额外工作树；需复用本地工具缓存，所有旧报告保留。
Pre-flight: 第七章构建器固定 target/validation；联合驱动需隔离并归档产物；第八章旧入口使用 current-run.txt，联合驱动需独立指定本轮目录。
任务1：结果收集及读回证据拒绝测试 RED→GREEN，3项通过。

任务2完成：18原样例双配置适配、10项不适用覆盖表、完整读回和哨兵，210运行通过。
任务3完成：Scala全量一次；路径适配故障定点补跑；有效590唯一测试通过。第七章RTL24，架构117、CoreMark6；既有第八章RTL1656；Python18通过。
任务4完成：独立复核两项问题已修复并验证；原生缺少完成/完成后副作用变异均拒绝；逐字和SHA审计通过，CPU与CVW状态未改。最终报告 /home/unlastingstar/cvw-experiment/pipeline/riscv-pipeline/docs/reports/chapter7-8-cvw/20261014T093158438412Z/report.md。
