# 07 AdrDecs

组合三个 AdrDec，按 BusConfig 选择 BootROM、RAM、APB 或 None。
bit0/1/2/3 分别表示 None/ROM/RAM/APB。RV32 支持字节、半字、字；
全部 3 位 HSIZE 都参与检查，4..7 不会截断后误选。

先加边界测试确认缺少实现时报错（`target/chapter9/modules/adr-decs/red.log`）。
实现后定向测试 1/1 通过，检查三窗口四边界与全部八种 HSIZE，含 34 位 RAM 地址。
GenerateAdrDecs 成功，生成 `target/chapter9/modules/adr-decs/rtl/AdrDecs.v`；
Verilator lint 退出 0。测试/生成日志见同目录 green.log，lint 日志见 lint.log。
未执行整机回归，留待所有模块集成。
