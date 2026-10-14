> 历史证据已迁至 [target 中的证据目录](../target/reports/)。`make clean` 会删除原始证据，Markdown 报告保留；重新运行相应报告命令将产生新的一次运行。

# 文档索引

## 当前开发

- [第七、八章验证入口与能力边界](chapter7-8-verification-update.md)
- [新增测试、差分、覆盖与失败定位](verification-guide.md)
- [后续模块与验证路线](verification-roadmap.md)
- [流水线时序与顶层接口](interfaces.md)
- [Chisel 编码规范](chisel-coding-standard.md)
- [架构常量与字段对应关系](constants-refactor/README.md)

## 设计与实施记录

- [第七章流水线](chapter7-pipeline.md)
- [第八章模块与整机集成](chapter8/README.md)
- [CVW 来源与平台适配](chapter7-8-cvw.md)

## 历史验收报告

这些报告的测试数量、周期和通过结论只对应各次运行；当前结果以新运行的逐例记录为准。

- [第七章验收](chapter7-validation.md)
- [第七章性能与正确性](chapter7-performance-correctness-report.md)
- [第八章详细测试报告](chapter8/16-testplan-report.md)
- [统一验证报告目录](reports/verification)
- [第七、八章联合报告目录](reports/chapter7-8-cvw)

## 产物与清理约定

`docs/` 只保留 Markdown。报告之外的 JSON、CSV、日志、波形、二进制、镜像、
源码快照和 SHA-256 清单全部位于 `target/`。报告链接使用相对路径关联原始证据。

`make clean SBT=./scripts/sbt-local.sh` 会删除整个 `target/`，包含运行证据和本地缓存，
保留本目录中的报告。重新运行报告命令会产生新的运行目录及新报告；历史证据不会凭空恢复。
迁移前已在 Git 中的产物从当前版本移除，不改写历史提交。
