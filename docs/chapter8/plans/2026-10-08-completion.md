# 第八章剩余阶段实施设计与计划

> 使用 superpowers:subagent-driven-development 或 executing-plans 按任务实施；先失败测试，再实现，再验证及提交。用户于本轮明确改为连续推进到结束，不等待逐模块审批。

**目标：**完成路线第 9–15 项，在 RV32 五级流水线上执行 CSR、M/S/U 陷阱及返回、中断、WFI 和数据端序操作，保留第七章回归入口。
**依据：**第八章书页335–381/PDF370–416，本地CVW privmode/privdec/trap/csr/privileged/endianswap，已交付01–08文档与模块。
**工具链：**JDK17、sbt1.10.7、Scala2.13.14、Chisel3.6.1、chiseltest0.6.2，不升级依赖。
**执行方式：**独立叶模块可并行准备，共享Scala构建和Git提交串行；总体连接由主代理实施，最后独立审查和统一交付。

## 范围与架构决定

- 沿用显式clk、高有效Bool同步reset，组合叶模块使用RawModule；95列、两空格。
- U/M始终支持，S可选；集成使用Bare地址空间，不实现MMU/PMP/虚拟化/浮点/CLINT/PLIC本体。
- 保持 `RiscvPipeline` 的既有六个观察输出及第七章默认配置。增加 `PrivilegedPipeline` 顶层，共享相同IFU/IEU/LSU/流水线实现，提供同域中断及64位time输入。
- `CpuConfig.privilegedSupported=false` 保留第七章默认行为；特权入口设置true，并持有 `PrivilegedConfig`。第八章验收必须实际使用特权入口，不能仅测独立模块。
- CSR状态、模式及M级store在同一沿提交；M级Trap取消本指令普通写回/store及年轻E/D/F，保留较老W写回。CSR写、返回及Fence提交后冲刷年轻指令，重取本指令PC+4或指定目标。
- CSR读按load结果处理：E到D依赖停一拍，M不前递CSR读结果，W提供真实CSR旧值。寄存器CSR源采用执行级前递值。
- WFI保持M并冻结E/D/F；较老W写回后清空，避免重复写。待pending或有界超时恢复。pending不依赖全局IE；WFI正常完成后，在后继边界接受中断。
- 当前事件取消和自身产生的流水冲刷分开。特权状态模块FlushM不直接接自身Trap/Ret冲刷；数据通路FlushM杀死进入M的年轻E指令。
- IALIGN=32。被采用的分支/JAL/JALR目标不对齐异常归属于跳转指令；错误路径上的故障被冲刷。
- 第八章ROM映射为包含resetVector的容量对齐窗口；RAM映射0..dmemDepth*4-1。越界或非自然对齐访问陷阱，禁止store。第七章保留旧回绕/无效访问策略。
- FENCE在本平台同步顺序RAM上作完成屏障；FENCE.I清空年轻取指并重取。IROM不可写，未增加自修改代码支持。SFENCE.VMA/MMU能力明确未覆盖。
- 数据端序由CSRStatus按实际模式/MPRV选择。读整字交换，再按访问宽度调整子字偏移；写重复字交换，物理字节掩码仍依地址。
- 中断输入须已同步；time须同域一致。提供外部接口而不虚构平台中断控制器。

## 公共类型

`PrivilegedConfig(supervisorSupported: Boolean=true, bigEndianSupported: Boolean=true,
 sstcSupported: Boolean=false, vectoredInterruptsSupported: Boolean=true,
 hpmCounters: Int=0, hartId: BigInt=0, wfiTimeoutCycles: Int=65536)`。
校验Sstc需要S、HPM0..29、hartId无符号32位、WFI阈值1..2^24。
`InterruptInputs`字段：MExtInt、SExtInt、MTimerInt、MSwInt（Bool），TIME_REGW（UInt64）。

## Task 9：Trap

文件privileged/Trap.scala、GenerateTrap.scala、privileged/TrapSpec.scala、09-trap.md。
`Trap(supervisorSupported:Boolean=true)`组合RawModule。
输入Bool：reset、ValidM、CommittedM、CommittedF、wfiM、wfiW、STATUS_MIE、STATUS_SIE，
InstrMisalignedFaultM、InstrAccessFaultM、InstrPageFaultM、IllegalInstrFaultM、BreakpointFaultM、EcallFaultM、LoadMisalignedFaultM、StoreAmoMisalignedFaultM、LoadAccessFaultM、StoreAmoAccessFaultM、LoadPageFaultM、StoreAmoPageFaultM。
输入UInt：PrivilegeModeW2、MIP_REGW12、MIE_REGW12、MIDELEG_REGW12、MEDELEG_REGW16。
输出Bool：TrapM、InterruptM、ExceptionM、IntPendingM、DelegateM；CauseM UInt5。
只采支持模式和中断位0xaaa(S开启)/0x888(S关闭)。IntPending为局部enable与pending，无全局IE/提交门控，reset时0。
普通陷阱需ValidM&&!reset。CommittedF暂缓全部陷阱；CommittedM暂缓中断；wfiM&&!wfiW推迟中断。
M未委托中断在低模式或MIE=1时可用；S委托中断只在U或S且SIE=1时可用。M的委托位不能使陷阱降到S。
中断优先异常；M-target中断优先S-target，各组11/3/7/9/1/5（实现位），与CVW一致。
异常优先12、1、2、0、3、ECALL8/9/11、6、4、15、13、7、5；本平台未对齐总是陷阱。
Delegate仅实际陷阱且当前U/S且对应DELEG位。没有事件输出cause0/delegate0。
- [ ] 写独立预期测试，完整权限/优先/有效/提交/WFI/配置矩阵；观察骨架失败。
- [ ] 实现；testOnly TrapSpec通过；两配置RTL/lint；中文记录；主代理提交。

## Task 10–11：WFI、EndianSwap

文件各模块、Generate入口、Spec及10/11文档。
`WFI(timeoutCycles:Int=65536)`显式时钟、同步reset。输入Bool ValidM/wfiM/FlushM/TrapM/IntPendingM；输出Bool WFIStallM/WFITimeoutM，WaitCycles UInt(log2Ceil(timeoutCycles+1).W)。
活跃Valid&&wfi&&!Flush&&!Trap期间无pending时每沿递增并饱和，达到阈值后Timeout；reset/取消/无WFI/pending清零。Stall为活跃&&!pending&&!timeout；未活跃输出Timeout0。计数不受自身stall阻止。
Timeout不判断TW/模式，由PrivDecode转换为非法异常；M和未限制S可有界正常恢复。
`EndianSwap`固定RV32组合RawModule，a/y UInt32、BigEndianM Bool，开启时颠倒4个字节，否则原值。
- [ ] 手工/随机/复位/阈值最小与非二次幂/取消/唤醒测试先失败后通过。
- [ ] 检查无锁存器/同步RTL，lint与中文说明；主代理逐阶段提交。

## Task 12：CSR 汇总

文件privileged/CSR.scala、privileged/PrivilegedConfig.scala、GenerateCSR.scala、CSRSpec.scala、12-csr.md。
`CSR(config:PrivilegedConfig=PrivilegedConfig())`显式clk/reset，连接已有Access/Status/Interrupt/Machine/Supervisor/Counters，不复制状态。
输入UInt：InstrM32、PCM32、IEUAdrM32、SrcAM32、PrivilegeModeW2、NextPrivilegeModeM2、CauseM5、HPMEventsM32、TIME_REGW64。
输入Bool：ValidM/StallW/FlushM/TrapM/InterruptM/mretM/sretM/InstrRetiredM/MExtInt/SExtInt/MTimerInt/MSwInt。
输出Bool：IsCSRInstrM/CSRReadM/CSRWriteM/IllegalCSRAccessM/STATUS_SPP/STATUS_TSR/STATUS_TVM/STATUS_TW/STATUS_MIE/STATUS_SIE/BigEndianM。
输出UInt：CSRResultM32、TrapVectorM32、EPCM32、STATUS_MPP2、MIP_REGW12、MIE_REGW12、MIDELEG_REGW12、MEDELEG_REGW16、MSTATUS_REGW32、MCYCLE_REGW64、MINSTRET_REGW64。
存在性和权限独立于Trap/写请求，避免组合环；选择机器/监督/计数组的Read/Modify旧值。软件写统一屏蔽Trap，叶模块保留门控。状态共享strobes保持reset抑制。
Trap记录PCM，Cause=Cat(Interrupt,Cause)，tval：中断/ECALL0、非法Instr、取指访问/页错/断点PCM、地址类IEUAdrM。
向量选Next为M/S，base清低2位，vectored仅Interrupt且mode01时base+4*cause；返回EPC由mret选择MEPC否则SEPC。
共享Status/Interrupt连接唯一，Sstc输入来自Super.STimerInt，MENVCFG高位STCE，Counters取真实许可/inhibit和外部time。
- [ ] 六CSR指令跨组、旧值/内部修改值、只读与权限、共享视图、精确记录/向量/返回/停顿复位、两配置真实联动先失败后通过。
- [ ] 编译/RTL/lint/中文说明；主代理提交。

## Task 13：Privileged

文件privileged/Privileged.scala、GeneratePrivileged.scala、PrivilegedSpec.scala、13-privileged.md。
连接CSR、PrivMode、PrivDecode、Trap、WFI，IO透传M级instr/PC/源/地址/故障、外部中断/time、Valid/StallW/FlushM、wfiW、HPM。
输出Trap/Interrupt/Exception/Ret/wfi/等待/超时/CSR结果/模式/RedirectM/RedirectPCM以及状态供验证。
Redirect优先Trap→TrapVector、Ret→EPC、CSRWrite/Fence→PCM+4；只在有效非stall非flush非reset时对外提交。
IllegalIEU针对整数非法编码及非法Fence；系统权限由Decode/Access判断。所有异常被Trap独立汇总。
- [ ] 真实链路CSR写、ECALL记录、MRET/SRET、委托、各中断模式、WFI timeout/pending与reset测试先失败后通过。
- [ ] RTL/lint/中文说明；提交。

## Task 14：流水线连接

改CpuConfig/IFU/IEU/Datapath/Hazard/LSU；共享PipelineBase及新PrivilegedPipeline/生成入口。生产观察输出不加调试；测试包装层expose状态。
StageE/M保存完整Instr、PC、源值、取指访问故障、跳转未对齐和目标；CSR晚结果/寄存器源使用参与Hazard。
IFU M重定向优先E跳转和stall；保存取指故障与PC。LSU范围/未对齐标志归于M，Trap/等待/取消屏蔽写；Endian按M当前模式只在第八章开启。
Datapath在M等待时保持E/M，W消耗较老结果一次后清空。M重定向清E和进入M的年轻结果；当前Trap不能生成普通W写回。M CSR结果进入W。
- [ ] 先编写CPU级签名测试：CSR紧邻依赖、寄存器源前递、故障指令/年轻store不提交、老W写回、错误分支路径异常、全部地址异常/重定向优先、WFI等待和单次退休、大小端子字及MPRV。
- [ ] 观察失败；逐文件实现；test-chapter8及第七章pipeline通过；四类配置RTL/lint；提交。

## Task 15：整机验收与统一交付

文件Chapter8AcceptanceSpec及可复现程序/14流水线和15验收中文说明，更新README路线、Make入口。
- [ ] 汇编级M/S/U ECALL、委托/非委托、MRET/SRET、非法CSR、直接/向量中断、handler应答并返回、pending重入、WFI后继EPC与TW超时，检查模式/cause/epc/tval/内存签名/退休。
- [ ] 内部MIP软件与外部同步输入作为刺激，TIME由测试提供，不用不存在的CLINT/PLIC地址。
- [ ] 全Scala测试、现有第七章定向及RTL回归，必要时已有整机验收；不重复大型CoreMark。
- [ ] 检查新Scala风格/文档链接/生成属性，完整独立只读审查；重要问题修复并回归。
- [ ] 各阶段均验证后Git提交，最终工作树干净；统一报告实现范围、实际结果和未覆盖能力，不等待中间审批。

## Review Focus

1. Trap/Ret自身flush不能取消事件；较老W可完成，故障M及年轻store不可提交。
2. CSR读晚结果与源前递、连续写/读、rd/rs1=x0和硬件pending修改语义不能错。
3. WFI计数自身stall、饱和、TW与pending、返回EPC和背靠背等待不能丢事件。
4. 模式切换/端序及MPRV必须发生在同沿，年轻load不能消费旧环境的结果。
5. 非零resetVector的取指故障、越界/未对齐目标、错误路径异常及中断优先相互覆盖。
