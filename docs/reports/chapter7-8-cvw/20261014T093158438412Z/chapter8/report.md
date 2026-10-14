> 历史证据已迁至 [target 中的证据目录](../../../../../target/reports/chapter7-8-cvw/20261014T093158438412Z/chapter8/)。`make clean` 会删除原始证据，Markdown 报告保留；重新运行相应报告命令将产生新的一次运行。

# 第8章本轮结果

|测试组|状态计数|
|---|---|
|cvw|{"pass": 210, "not_applicable": 11}|
|cvw-checker-native-mutations|{"pass": 2}|
|cvw-checker-negative|{"pass": 12}|
|entrypoint|{"pass": 3}|
|existing-checker-negative|{"pass": 1}|
|existing-rtl|{"pass": 1656}|
|python-final|{"pass": 1}|
|recovery|{"build_platform_error": 1}|
|scala|{"pass": 468}|

## 失败和未执行

无。

入口计数保留最初命令的非零退出状态；已解决的平台故障以 results.json 的 resolved_by 指向定点补跑，不计入当前未解决失败。全量 Scala 唯一测试名共590项（第七章122，第八章468），全部已有本轮通过结果。
