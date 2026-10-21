# 10 AHBAPBBridge

参考 CVW `uncore/ahbapbbridge.sv`，保留桥和 Idle、Setup、Access 三态，表达非突发
AHB 到 APB 转换。地址接受条件为 `HSEL && HTRANS[1] && HREADY`；接受后保存
地址和写方向。Setup 输出 PSEL=1、PENABLE=0，下一拍 Access 输出 PENABLE=1；
PREADY 完成后返回 Idle。旧 Access 完成同沿接受下一地址时，下一事务仍完整经历 Setup。

2026-10-21 精简移除 `WriteData:32`、`Strobe:4` 和 `HeldReadData:32` 三个寄存器。
写数据相位直接连接 `PWDATA=HWDATA`、`PSTRB=HWSTRB`；AHB master 必须在
HREADY 等待期间保持数据和掩码。PADDR/PWRITE 仍由桥保存，PSEL/PENABLE 仍由桥
产生。真实上游 AHBInterface 已在地址接受沿保存写载荷，因此 APB 等待期间字段稳定。
读响应为 `HRDATA = PSEL ? PRDATA : 0`，对应 CVW 多外设响应选择的单外设形式；
Setup 和 Access 期间返回当前 PRDATA，Idle 返回零，不再保持上次读值。
AHBInterface 在读完成沿捕获 FetchBuffer，后续写或桥进入 Idle 均不覆盖该缓冲。
显式 clk/reset；Bool 同步复位；没有增加组合 `!reset` 请求或输出门控。

本轮验证及工作树/提交快照的边界见 [AHB/APB 桥精简验证报告](23-ahb-apb-simplification.md)。
桥用例扩展为7项：保留用户 ChiselEnum 的工作树第九章97/97通过，数字状态机的最终
提交索引快照关联21/21通过；该快照的桥 RTL 生成与严格 lint 均退出0。
该快照的整机矩阵144/144、独立报告及源码/SHA256SUMS核验均通过，运行报告为
[`20261021T064053783979Z`](../../target/chapter9-testplan/runs/20261021T064053783979Z/report.md)。
初版缺类红阶段、3/3 定向测试和生成/lint 是历史结果，原始证据仍保留于
`target/chapter9/modules/ahb-apb-bridge/`；其中原来的桥内读响应保持语义已由本次调整替代。
