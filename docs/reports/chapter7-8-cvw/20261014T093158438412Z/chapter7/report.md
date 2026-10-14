> 历史证据已迁至 [target 中的证据目录](../../../../../target/reports/chapter7-8-cvw/20261014T093158438412Z/chapter7/)。`make clean` 会删除原始证据，Markdown 报告保留；重新运行相应报告命令将产生新的一次运行。

# 第7章本轮结果

|测试组|状态计数|
|---|---|
|arch|{"pass": 117}|
|coremark|{"pass": 6}|
|entrypoint|{"build_platform_error": 1, "pass": 3}|
|rtl|{"pass": 24}|
|scala|{"pass": 122}|
|validation-checker-negative|{"pass": 2}|

## 失败和未执行

无。

## 第七章功能分组

同一测试可以涉及多个分组，以下计数不相加为独立总数。

|分组|测试数|状态|
|---|---|---|
|模块|100|{'pass': 100}|
|流水线冒险|7|{'pass': 7}|
|前递|8|{'pass': 8}|
|停顿|4|{'pass': 4}|
|冲刷|1|{'pass': 1}|
|访存|54|{'pass': 54}|
|跳转|4|{'pass': 4}|
|复位|8|{'pass': 8}|

入口计数保留最初命令的非零退出状态；已解决的平台故障以 results.json 的 resolved_by 指向定点补跑，不计入当前未解决失败。全量 Scala 唯一测试名共590项（第七章122，第八章468），全部已有本轮通过结果。
