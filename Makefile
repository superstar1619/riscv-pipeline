.DEFAULT_GOAL := help
export PYTHONPYCACHEPREFIX := $(CURDIR)/target/cache/python
SBT ?= sbt
VERILATOR ?= verilator
WIDTH ?= 32
TARGET_DIR ?= target/generated/extend
CMP_TARGET_DIR ?= target/generated/cmp
SHIFTER_TARGET_DIR ?= target/generated/shifter
SWBYTEMASK_TARGET_DIR ?= target/generated/swbytemask
SUBWORDWRITE_TARGET_DIR ?= target/generated/subwordwrite
SUBWORDREAD_TARGET_DIR ?= target/generated/subwordread
DTIM_TARGET_DIR ?= target/generated/dtim
ALU_TARGET_DIR ?= target/generated/alu
REGFILE_TARGET_DIR ?= target/generated/regfile
REGISTER_COUNT ?= 32
CONTROLLER_TARGET_DIR ?= target/generated/controller
DATAPATH_TARGET_DIR ?= target/generated/datapath
IEU_TARGET_DIR ?= target/generated/ieu
IROM_TARGET_DIR ?= target/generated/irom
IMEM_DEPTH ?= 64
INSTRUCTION_INIT_FILE ?= programs/riscvtest.memfile
IFU_TARGET_DIR ?= target/generated/ifu
RESET_VECTOR ?= 0
LSU_TARGET_DIR ?= target/generated/lsu
DMEM_DEPTH ?= 64
CPU_TARGET_DIR ?= target/generated/riscv-pipeline
CSR_ACCESS_TARGET_DIR ?= target/generated/csr-access
PRIV_DECODE_TARGET_DIR ?= target/generated/priv-decode
PRIV_DECODE_SUPERVISOR_SUPPORTED ?= true
PRIV_DECODE_VIRTUAL_MEMORY_SUPPORTED ?= false
CSR_STATUS_TARGET_DIR ?= target/generated/csr-status
CSR_STATUS_SUPERVISOR_SUPPORTED ?= true
CSR_STATUS_VIRTUAL_MEMORY_SUPPORTED ?= false
CSR_STATUS_BIG_ENDIAN_SUPPORTED ?= false
CSR_INTERRUPT_TARGET_DIR ?= target/generated/csr-interrupt
CSR_INTERRUPT_SUPERVISOR_SUPPORTED ?= true
CSR_INTERRUPT_SSTC_SUPPORTED ?= false
CSR_MACHINE_TARGET_DIR ?= target/generated/csr-machine
CSR_MACHINE_SUPERVISOR_SUPPORTED ?= true
CSR_MACHINE_VIRTUAL_MEMORY_SUPPORTED ?= false
CSR_MACHINE_SSTC_SUPPORTED ?= false
CSR_MACHINE_VECTORED_INTERRUPTS_SUPPORTED ?= true
CSR_MACHINE_HPM_COUNTERS ?= 0
CSR_MACHINE_HART_ID ?= 0
CSR_SUPERVISOR_TARGET_DIR ?= target/generated/csr-supervisor
CSR_SUPERVISOR_SUPPORTED ?= true
CSR_SUPERVISOR_VIRTUAL_MEMORY_SUPPORTED ?= false
CSR_SUPERVISOR_SSTC_SUPPORTED ?= false
CSR_SUPERVISOR_VECTORED_INTERRUPTS_SUPPORTED ?= true
CSR_SUPERVISOR_HPM_COUNTERS ?= 0
CSR_SUPERVISOR_ASID_BITS ?= 0
CSR_COUNTERS_TARGET_DIR ?= target/generated/csr-counters
CSR_COUNTERS_SUPERVISOR_SUPPORTED ?= true
CSR_COUNTERS_HPM_COUNTERS ?= 0
PRIV_MODE_TARGET_DIR ?= target/generated/priv-mode
PRIV_MODE_SUPERVISOR_SUPPORTED ?= true
TRAP_TARGET_DIR ?= target/generated/trap
TRAP_SUPERVISOR_SUPPORTED ?= true
WFI_TARGET_DIR ?= target/generated/wfi
WFI_TIMEOUT_CYCLES ?= 65536
ENDIAN_SWAP_TARGET_DIR ?= target/generated/endian-swap
CSR_TARGET_DIR ?= target/generated/csr
CSR_SUPERVISOR_SUPPORTED ?= true
CSR_BIG_ENDIAN_SUPPORTED ?= true
CSR_SSTC_SUPPORTED ?= false
CSR_HPM_COUNTERS ?= 0
VALIDATION_SUITE ?= all
VALIDATION_BUILD_DIR ?= target/validation
VALIDATION_RTL_DIR ?= target/generated/validation

.PHONY: generate generate-cmp generate-shifter generate-swbytemask \
  generate-subwordwrite generate-subwordread generate-dtim generate-alu \
  generate-regfile generate-controller generate-datapath generate-ieu \
  generate-irom generate-ifu generate-lsu generate-cpu test test-pipeline test-rtl clean

.PHONY: build-acceptance generate-acceptance test-acceptance generate-rtl-test
.PHONY: generate-csr-access test-csr-access
.PHONY: generate-priv-decode test-priv-decode
.PHONY: generate-csr-status test-csr-status
.PHONY: generate-csr-interrupt test-csr-interrupt
.PHONY: generate-csr-machine test-csr-machine
.PHONY: generate-csr-supervisor test-csr-supervisor
.PHONY: generate-csr-counters test-csr-counters
.PHONY: generate-priv-mode test-priv-mode
.PHONY: build-validation generate-validation test-validation test-arch test-coremark test-validation-builder

test-validation-builder:
	python3 -m unittest discover -s src/test/python -v

build-validation: test-validation-builder
	VALIDATION_BUILD_DIR="$(VALIDATION_BUILD_DIR)" python3 scripts/build/build-validation.py $(VALIDATION_SUITE)

generate-validation: build-validation
	$(SBT) "runMain riscvpipeline.generators.GenerateRiscvPipeline 524288 32768 $(VALIDATION_RTL_DIR) $(VALIDATION_BUILD_DIR)/program.memfile 0x80000000"

test-validation: generate-validation
	VERILATOR="$(VERILATOR)" VALIDATION_BUILD_DIR="$(VALIDATION_BUILD_DIR)" VALIDATION_RTL_FILE="$(VALIDATION_RTL_DIR)/RiscvPipeline.v" bash scripts/run/test-validation.sh $(VALIDATION_SUITE)

test-arch:
	$(MAKE) test-validation VALIDATION_SUITE=arch

test-coremark:
	$(MAKE) test-validation VALIDATION_SUITE=coremark

build-acceptance:
	python3 scripts/build/build-rv32-acceptance.py

generate-acceptance: build-acceptance
	$(SBT) "runMain riscvpipeline.generators.GenerateRiscvPipeline 128 64 target/generated/rv32-acceptance target/acceptance-images/rv32-acceptance.memfile 0" "runMain riscvpipeline.generators.GenerateRiscvPipeline 256 128 target/generated/rv32-acceptance-expanded target/acceptance-images/rv32-acceptance-expanded.memfile 0x100"

test-acceptance:
	$(SBT) 'testOnly riscvpipeline.CpuAcceptanceSpec riscvpipeline.PipelineSmokeSpec'

generate:
	$(SBT) "runMain riscvpipeline.generators.GenerateExtend $(WIDTH) $(TARGET_DIR)"

generate-cmp:
	$(SBT) "runMain riscvpipeline.generators.GenerateCmp $(WIDTH) $(CMP_TARGET_DIR)"

generate-shifter:
	$(SBT) "runMain riscvpipeline.generators.GenerateShifter $(WIDTH) $(SHIFTER_TARGET_DIR)"

generate-swbytemask:
	$(SBT) "runMain riscvpipeline.generators.GenerateSwByteMask $(WIDTH) $(SWBYTEMASK_TARGET_DIR)"

generate-subwordwrite:
	$(SBT) "runMain riscvpipeline.generators.GenerateSubwordWrite $(WIDTH) $(SUBWORDWRITE_TARGET_DIR)"

generate-subwordread:
	$(SBT) "runMain riscvpipeline.generators.GenerateSubwordRead $(WIDTH) $(SUBWORDREAD_TARGET_DIR)"

generate-dtim:
	$(SBT) "runMain riscvpipeline.generators.GenerateDTIM $(WIDTH) $(DMEM_DEPTH) $(DTIM_TARGET_DIR)"

generate-alu:
	$(SBT) "runMain riscvpipeline.generators.GenerateALU $(WIDTH) $(ALU_TARGET_DIR)"

generate-regfile:
	$(SBT) "runMain riscvpipeline.generators.GenerateRegFile $(WIDTH) $(REGFILE_TARGET_DIR) $(REGISTER_COUNT)"

generate-controller:
	$(SBT) "runMain riscvpipeline.generators.GenerateController $(CONTROLLER_TARGET_DIR)"

generate-datapath:
	$(SBT) "runMain riscvpipeline.generators.GenerateDatapath $(DATAPATH_TARGET_DIR)"

generate-ieu:
	$(SBT) "runMain riscvpipeline.generators.GenerateIEU $(IEU_TARGET_DIR)"

generate-irom:
	$(SBT) "runMain riscvpipeline.generators.GenerateIROM $(IMEM_DEPTH) $(IROM_TARGET_DIR) $(INSTRUCTION_INIT_FILE)"

generate-ifu:
	$(SBT) "runMain riscvpipeline.generators.GenerateIFU $(IMEM_DEPTH) $(IFU_TARGET_DIR) $(INSTRUCTION_INIT_FILE) $(RESET_VECTOR)"

generate-lsu:
	$(SBT) "runMain riscvpipeline.generators.GenerateLSU $(DMEM_DEPTH) $(LSU_TARGET_DIR)"

generate-cpu:
	$(SBT) "runMain riscvpipeline.generators.GenerateRiscvPipeline $(IMEM_DEPTH) $(DMEM_DEPTH) $(CPU_TARGET_DIR) $(INSTRUCTION_INIT_FILE) $(RESET_VECTOR)"

test-pipeline:
	$(SBT) 'testOnly riscvpipeline.PipelineHazardSpec riscvpipeline.PipelineMemorySpec riscvpipeline.PipelineSmokeSpec'

generate-csr-access:
	$(SBT) "runMain riscvpipeline.generators.GenerateCSRAccess $(CSR_ACCESS_TARGET_DIR)"

test-csr-access:
	$(SBT) 'testOnly riscvpipeline.privileged.CSRAccessSpec'

generate-priv-decode:
	$(SBT) "runMain riscvpipeline.generators.GeneratePrivDecode $(PRIV_DECODE_TARGET_DIR) $(PRIV_DECODE_SUPERVISOR_SUPPORTED) $(PRIV_DECODE_VIRTUAL_MEMORY_SUPPORTED)"

test-priv-decode:
	$(SBT) 'testOnly riscvpipeline.privileged.PrivDecodeSpec'

generate-csr-status:
	$(SBT) "runMain riscvpipeline.generators.GenerateCSRStatus $(CSR_STATUS_TARGET_DIR) $(CSR_STATUS_SUPERVISOR_SUPPORTED) $(CSR_STATUS_VIRTUAL_MEMORY_SUPPORTED) $(CSR_STATUS_BIG_ENDIAN_SUPPORTED)"

test-csr-status:
	$(SBT) 'testOnly riscvpipeline.privileged.CSRStatusSpec'

generate-csr-interrupt:
	$(SBT) "runMain riscvpipeline.generators.GenerateCSRInterrupt $(CSR_INTERRUPT_TARGET_DIR) $(CSR_INTERRUPT_SUPERVISOR_SUPPORTED) $(CSR_INTERRUPT_SSTC_SUPPORTED)"

test-csr-interrupt:
	$(SBT) 'testOnly riscvpipeline.privileged.CSRInterruptSpec'

generate-csr-machine:
	$(SBT) "runMain riscvpipeline.generators.GenerateCSRMachine $(CSR_MACHINE_TARGET_DIR) $(CSR_MACHINE_SUPERVISOR_SUPPORTED) $(CSR_MACHINE_VIRTUAL_MEMORY_SUPPORTED) $(CSR_MACHINE_SSTC_SUPPORTED) $(CSR_MACHINE_VECTORED_INTERRUPTS_SUPPORTED) $(CSR_MACHINE_HPM_COUNTERS) $(CSR_MACHINE_HART_ID)"

test-csr-machine:
	$(SBT) 'testOnly riscvpipeline.privileged.CSRMachineSpec'

generate-csr-supervisor:
	$(SBT) "runMain riscvpipeline.generators.GenerateCSRSupervisor $(CSR_SUPERVISOR_TARGET_DIR) $(CSR_SUPERVISOR_SUPPORTED) $(CSR_SUPERVISOR_VIRTUAL_MEMORY_SUPPORTED) $(CSR_SUPERVISOR_SSTC_SUPPORTED) $(CSR_SUPERVISOR_VECTORED_INTERRUPTS_SUPPORTED) $(CSR_SUPERVISOR_HPM_COUNTERS) $(CSR_SUPERVISOR_ASID_BITS)"

test-csr-supervisor:
	$(SBT) 'testOnly riscvpipeline.privileged.CSRSupervisorSpec'

generate-csr-counters:
	$(SBT) "runMain riscvpipeline.generators.GenerateCSRCounters $(CSR_COUNTERS_TARGET_DIR) $(CSR_COUNTERS_SUPERVISOR_SUPPORTED) $(CSR_COUNTERS_HPM_COUNTERS)"

test-csr-counters:
	$(SBT) 'testOnly riscvpipeline.privileged.CSRCountersSpec'

generate-priv-mode:
	$(SBT) "runMain riscvpipeline.generators.GeneratePrivMode $(PRIV_MODE_TARGET_DIR) $(PRIV_MODE_SUPERVISOR_SUPPORTED)"

test-priv-mode:
	$(SBT) 'testOnly riscvpipeline.privileged.PrivModeSpec'

test:
	$(SBT) test

# 使用固定配置重新生成 RTL，避免新工程依赖旧导出文件。
generate-rtl-test: build-acceptance
	$(SBT) "runMain riscvpipeline.generators.GenerateRiscvPipeline 64 64 target/generated/riscv-pipeline programs/riscvtest.memfile 0" \
	  "runMain riscvpipeline.generators.GenerateRiscvPipeline 128 128 target/generated/riscv-pipeline128 programs/rv32-configtest.memfile 0x100" \
	  "runMain riscvpipeline.generators.GenerateRiscvPipeline 128 64 target/generated/rv32-acceptance target/acceptance-images/rv32-acceptance.memfile 0" \
	  "runMain riscvpipeline.generators.GenerateRiscvPipeline 256 128 target/generated/rv32-acceptance-expanded target/acceptance-images/rv32-acceptance-expanded.memfile 0x100"

test-rtl: generate-rtl-test
	VERILATOR="$(VERILATOR)" ./scripts/run/test-rtl.sh

clean:
	$(SBT) clean
	python3 -c "from pathlib import Path; import shutil; p = Path('target'); shutil.rmtree(p) if p.exists() else None"

.PHONY: help
help:
	@echo '从项目根目录执行；本地工具链示例：make <目标> SBT=./scripts/sbt-local.sh'
	@echo 'test-verification    默认冒烟；VERIFY_SUITE=all VERIFY_PROFILE=full 执行完整回归'
	@echo 'test-diff            RV32I 逐提交差分；可设置 VERIFY_PROFILE=full'
	@echo 'report-verification 完整回归并发布 Markdown 到 docs/reports；证据保存在 target/'
	@echo 'generate-cpu         生成 RV32I RTL；generate-privileged-cpu 生成特权 RTL'
	@echo 'test-pipeline        流水线测试；test-chapter8 特权测试；test-rtl 生产 RTL 验收'
	@echo 'report-chapter8      第八章详细报告；report-chapter7-8-cvw 联合报告'
	@echo 'clean               删除全部 target/，包括缓存、镜像、波形和原始验证证据；保留 Markdown 报告'

.PHONY: generate-trap test-trap
generate-trap:
	$(SBT) "runMain riscvpipeline.generators.GenerateTrap $(TRAP_TARGET_DIR) $(TRAP_SUPERVISOR_SUPPORTED)"

test-trap:
	$(SBT) 'testOnly riscvpipeline.privileged.TrapSpec'

.PHONY: generate-wfi test-wfi
generate-wfi:
	$(SBT) "runMain riscvpipeline.generators.GenerateWFI $(WFI_TARGET_DIR) $(WFI_TIMEOUT_CYCLES)"

test-wfi:
	$(SBT) 'testOnly riscvpipeline.privileged.WFISpec'

.PHONY: generate-endian-swap test-endian-swap
generate-endian-swap:
	$(SBT) "runMain riscvpipeline.generators.GenerateEndianSwap $(ENDIAN_SWAP_TARGET_DIR)"

test-endian-swap:
	$(SBT) 'testOnly riscvpipeline.privileged.EndianSwapSpec'

.PHONY: generate-csr test-csr
generate-csr:
	$(SBT) "runMain riscvpipeline.generators.GenerateCSR $(CSR_TARGET_DIR) $(CSR_SUPERVISOR_SUPPORTED) $(CSR_BIG_ENDIAN_SUPPORTED) $(CSR_SSTC_SUPPORTED) $(CSR_HPM_COUNTERS)"

test-csr:
	$(SBT) 'testOnly riscvpipeline.privileged.CSRSpec'

PRIVILEGED_TARGET_DIR ?= target/generated/privileged
PRIVILEGED_SUPERVISOR_SUPPORTED ?= true
PRIVILEGED_BIG_ENDIAN_SUPPORTED ?= true
PRIVILEGED_SSTC_SUPPORTED ?= false
PRIVILEGED_HPM_COUNTERS ?= 0
PRIVILEGED_WFI_TIMEOUT_CYCLES ?= 65536
.PHONY: generate-privileged test-privileged
generate-privileged:
	$(SBT) "runMain riscvpipeline.generators.GeneratePrivileged $(PRIVILEGED_TARGET_DIR) $(PRIVILEGED_SUPERVISOR_SUPPORTED) $(PRIVILEGED_BIG_ENDIAN_SUPPORTED) $(PRIVILEGED_SSTC_SUPPORTED) $(PRIVILEGED_HPM_COUNTERS) $(PRIVILEGED_WFI_TIMEOUT_CYCLES)"

test-privileged:
	$(SBT) 'testOnly riscvpipeline.privileged.PrivilegedSpec'

PRIVILEGED_CPU_TARGET_DIR ?= target/generated/privileged-pipeline
.PHONY: generate-privileged-cpu test-chapter8
generate-privileged-cpu:
	$(SBT) "runMain riscvpipeline.generators.GeneratePrivilegedPipeline $(IMEM_DEPTH) $(DMEM_DEPTH) $(PRIVILEGED_CPU_TARGET_DIR) $(INSTRUCTION_INIT_FILE) $(RESET_VECTOR) $(PRIVILEGED_SUPERVISOR_SUPPORTED) $(PRIVILEGED_BIG_ENDIAN_SUPPORTED) $(PRIVILEGED_SSTC_SUPPORTED) $(PRIVILEGED_HPM_COUNTERS) $(PRIVILEGED_WFI_TIMEOUT_CYCLES)"

test-chapter8:
	$(SBT) 'testOnly riscvpipeline.Chapter8AcceptanceSpec'

CHAPTER8_OUTPUT = $$(python3 scripts/report/report-chapter8.py --path)
CHAPTER8_JAVA_OPTS ?= -XX:ActiveProcessorCount=4 -Xmx3g
.PHONY: test-chapter8-plan test-chapter8-rtl report-chapter8
# Shared Scala fixtures live in Test scope; the exporter emits independent expectations.
test-chapter8-plan:
	mkdir -p target/chapter8-testplan
	JAVA_TOOL_OPTIONS="$(JAVA_TOOL_OPTIONS) $(CHAPTER8_JAVA_OPTS)" $(SBT) 'Test / runMain riscvpipeline.ExportChapter8TestPlan' 'testOnly riscvpipeline.Chapter8TestPlanSpec' > "$(CHAPTER8_OUTPUT)/scala.log" 2>&1 || { tail -80 "$(CHAPTER8_OUTPUT)/scala.log"; exit 1; }

test-chapter8-rtl:
	SBT="$(SBT)" VERILATOR="$(VERILATOR)" python3 scripts/run/test-chapter8-rtl.py

# Requires fresh tests and archives the evidence from this invocation.
report-chapter8:
	python3 scripts/report/report-chapter8.py --begin
	$(MAKE) test-chapter8-rtl SBT="$(SBT)" VERILATOR="$(VERILATOR)"
	env -u CHAPTER8_CASE_FILTER JAVA_TOOL_OPTIONS="$(JAVA_TOOL_OPTIONS) $(CHAPTER8_JAVA_OPTS)" $(SBT) "set Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, \"-u\", \"$(CHAPTER8_OUTPUT)/junit\")" compile test > "$(CHAPTER8_OUTPUT)/regression.log" 2>&1 || { tail -80 "$(CHAPTER8_OUTPUT)/regression.log"; exit 1; }
	$(MAKE) test-rtl SBT="$(SBT)" > "$(CHAPTER8_OUTPUT)/chapter7-rtl.log" 2>&1 || { tail -80 "$(CHAPTER8_OUTPUT)/chapter7-rtl.log"; exit 1; }
	python3 -m unittest discover -s src/test/python -v > "$(CHAPTER8_OUTPUT)/python.log" 2>&1 || { cat "$(CHAPTER8_OUTPUT)/python.log"; exit 1; }
	python3 scripts/report/report-chapter8.py

# Fresh joint evidence; reports are emitted even when a CPU case fails.
.PHONY: test-chapter8-cvw test-chapter7-8-cvw report-chapter7-8-cvw
CVW_ROOT ?= /home/unlastingstar/cvw
test-chapter8-cvw:
	CVW_ROOT="$(CVW_ROOT)" SBT="$(SBT)" VERILATOR="$(VERILATOR)" python3 scripts/run/run-chapter8-cvw.py

test-chapter7-8-cvw report-chapter7-8-cvw:
	CVW_ROOT="$(CVW_ROOT)" SBT="$(SBT)" VERILATOR="$(VERILATOR)" python3 scripts/run/test-chapter7-8-cvw.py

# Shared chapter 5.11.3 program-level verification workflow.
VERIFY_SUITE ?= all
VERIFY_PROFILE ?= smoke
.PHONY: test-verification test-diff report-verification
test-verification:
	CVW_ROOT="$(CVW_ROOT)" SBT="$(SBT)" VERILATOR="$(VERILATOR)" python3 scripts/run/verify.py --suite "$(VERIFY_SUITE)" --profile "$(VERIFY_PROFILE)"

test-diff:
	CVW_ROOT="$(CVW_ROOT)" SBT="$(SBT)" VERILATOR="$(VERILATOR)" python3 scripts/run/verify.py --suite diff --profile "$(VERIFY_PROFILE)"

report-verification:
	CVW_ROOT="$(CVW_ROOT)" SBT="$(SBT)" VERILATOR="$(VERILATOR)" python3 scripts/run/verify.py --archive
