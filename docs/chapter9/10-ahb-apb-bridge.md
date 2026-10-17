# 10 AHBAPBBridge

参考 CVW `uncore/ahbapbbridge.sv`，用 Idle、Setup、Access 表达非突发 AHB 到 APB 转换。
地址接受条件为 HSEL && HTRANS[1] && HREADY。接受后保留地址/写方向，
Setup 输出 PSEL=1、PENABLE=0；下一拍 Access 输出 PENABLE=1，PREADY 完成后返回 Idle。
AHB 写数据和字节掩码属于数据相位，在 Setup 末沿保存，避免采到前一事务的数据。
Access 等待中 PADDR/PWRITE/PWDATA/PSTRB 均保持；完成读响应保持到下一事务。
旧 Access 完成同沿接受下一地址时，下一事务仍完整经历 Setup。
显式 clk/reset；Bool 同步复位；没有组合 !reset 请求/输出门控。

缺类红阶段日志见 `target/chapter9/modules/ahb-apb-bridge/red.log`。
定向测试 3/3 通过，覆盖相位顺序、四拍等待字段保持、数据相位采样、读响应保留、
背靠背事务、HREADY=0 不接受及同步复位。生成与 Verilator lint 均成功。
证据为同目录 green.log、lint.log、rtl/AHBAPBBridge.v。未执行整机回归。
