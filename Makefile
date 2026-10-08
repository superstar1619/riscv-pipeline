SBT ?= sbt
VERILATOR ?= verilator
WIDTH ?= 32
TARGET_DIR ?= generated/extend
CMP_TARGET_DIR ?= generated/cmp
SHIFTER_TARGET_DIR ?= generated/shifter
SWBYTEMASK_TARGET_DIR ?= generated/swbytemask
SUBWORDWRITE_TARGET_DIR ?= generated/subwordwrite
SUBWORDREAD_TARGET_DIR ?= generated/subwordread
DTIM_TARGET_DIR ?= generated/dtim
ALU_TARGET_DIR ?= generated/alu
REGFILE_TARGET_DIR ?= generated/regfile
REGISTER_COUNT ?= 32
CONTROLLER_TARGET_DIR ?= generated/controller
DATAPATH_TARGET_DIR ?= generated/datapath
IEU_TARGET_DIR ?= generated/ieu
IROM_TARGET_DIR ?= generated/irom
IMEM_DEPTH ?= 64
INSTRUCTION_INIT_FILE ?= programs/riscvtest.memfile
IFU_TARGET_DIR ?= generated/ifu
RESET_VECTOR ?= 0
LSU_TARGET_DIR ?= generated/lsu
DMEM_DEPTH ?= 64
CPU_TARGET_DIR ?= generated/riscv-pipeline
CSR_ACCESS_TARGET_DIR ?= generated/csr-access
PRIV_DECODE_TARGET_DIR ?= generated/priv-decode
PRIV_DECODE_SUPERVISOR_SUPPORTED ?= true
PRIV_DECODE_VIRTUAL_MEMORY_SUPPORTED ?= false
CSR_STATUS_TARGET_DIR ?= generated/csr-status
CSR_STATUS_SUPERVISOR_SUPPORTED ?= true
CSR_STATUS_VIRTUAL_MEMORY_SUPPORTED ?= false
CSR_STATUS_BIG_ENDIAN_SUPPORTED ?= false
CSR_INTERRUPT_TARGET_DIR ?= generated/csr-interrupt
CSR_INTERRUPT_SUPERVISOR_SUPPORTED ?= true
CSR_INTERRUPT_SSTC_SUPPORTED ?= false
CSR_MACHINE_TARGET_DIR ?= generated/csr-machine
CSR_MACHINE_SUPERVISOR_SUPPORTED ?= true
CSR_MACHINE_VIRTUAL_MEMORY_SUPPORTED ?= false
CSR_MACHINE_SSTC_SUPPORTED ?= false
CSR_MACHINE_VECTORED_INTERRUPTS_SUPPORTED ?= true
CSR_MACHINE_HPM_COUNTERS ?= 0
CSR_MACHINE_HART_ID ?= 0
CSR_SUPERVISOR_TARGET_DIR ?= generated/csr-supervisor
CSR_SUPERVISOR_SUPPORTED ?= true
CSR_SUPERVISOR_VIRTUAL_MEMORY_SUPPORTED ?= false
CSR_SUPERVISOR_SSTC_SUPPORTED ?= false
CSR_SUPERVISOR_VECTORED_INTERRUPTS_SUPPORTED ?= true
CSR_SUPERVISOR_HPM_COUNTERS ?= 0
CSR_SUPERVISOR_ASID_BITS ?= 0
CSR_COUNTERS_TARGET_DIR ?= generated/csr-counters
CSR_COUNTERS_SUPERVISOR_SUPPORTED ?= true
CSR_COUNTERS_HPM_COUNTERS ?= 0
PRIV_MODE_TARGET_DIR ?= generated/priv-mode
PRIV_MODE_SUPERVISOR_SUPPORTED ?= true
TRAP_TARGET_DIR ?= generated/trap
TRAP_SUPERVISOR_SUPPORTED ?= true
WFI_TARGET_DIR ?= generated/wfi
WFI_TIMEOUT_CYCLES ?= 65536
ENDIAN_SWAP_TARGET_DIR ?= generated/endian-swap
CSR_TARGET_DIR ?= generated/csr
CSR_SUPERVISOR_SUPPORTED ?= true
CSR_BIG_ENDIAN_SUPPORTED ?= true
CSR_SSTC_SUPPORTED ?= false
CSR_HPM_COUNTERS ?= 0
VALIDATION_SUITE ?= all

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
	python3 scripts/build-validation.py $(VALIDATION_SUITE)

generate-validation: build-validation
	$(SBT) "runMain riscvpipeline.GenerateRiscvPipeline 524288 32768 generated/validation target/validation/program.memfile 0x80000000"

test-validation: generate-validation
	VERILATOR="$(VERILATOR)" bash scripts/test-validation.sh $(VALIDATION_SUITE)

test-arch:
	$(MAKE) test-validation VALIDATION_SUITE=arch

test-coremark:
	$(MAKE) test-validation VALIDATION_SUITE=coremark

build-acceptance:
	python3 scripts/build-rv32-acceptance.py

generate-acceptance:
	$(SBT) "runMain riscvpipeline.GenerateRiscvPipeline 128 64 generated/rv32-acceptance programs/rv32-acceptance.memfile 0" "runMain riscvpipeline.GenerateRiscvPipeline 256 128 generated/rv32-acceptance-expanded programs/rv32-acceptance-expanded.memfile 0x100"

test-acceptance:
	$(SBT) 'testOnly riscvpipeline.CpuAcceptanceSpec riscvpipeline.PipelineSmokeSpec'

generate:
	$(SBT) "runMain riscvpipeline.GenerateExtend $(WIDTH) $(TARGET_DIR)"

generate-cmp:
	$(SBT) "runMain riscvpipeline.GenerateCmp $(WIDTH) $(CMP_TARGET_DIR)"

generate-shifter:
	$(SBT) "runMain riscvpipeline.GenerateShifter $(WIDTH) $(SHIFTER_TARGET_DIR)"

generate-swbytemask:
	$(SBT) "runMain riscvpipeline.GenerateSwByteMask $(WIDTH) $(SWBYTEMASK_TARGET_DIR)"

generate-subwordwrite:
	$(SBT) "runMain riscvpipeline.GenerateSubwordWrite $(WIDTH) $(SUBWORDWRITE_TARGET_DIR)"

generate-subwordread:
	$(SBT) "runMain riscvpipeline.GenerateSubwordRead $(WIDTH) $(SUBWORDREAD_TARGET_DIR)"

generate-dtim:
	$(SBT) "runMain riscvpipeline.GenerateDTIM $(WIDTH) $(DMEM_DEPTH) $(DTIM_TARGET_DIR)"

generate-alu:
	$(SBT) "runMain riscvpipeline.GenerateALU $(WIDTH) $(ALU_TARGET_DIR)"

generate-regfile:
	$(SBT) "runMain riscvpipeline.GenerateRegFile $(WIDTH) $(REGFILE_TARGET_DIR) $(REGISTER_COUNT)"

generate-controller:
	$(SBT) "runMain riscvpipeline.GenerateController $(CONTROLLER_TARGET_DIR)"

generate-datapath:
	$(SBT) "runMain riscvpipeline.GenerateDatapath $(DATAPATH_TARGET_DIR)"

generate-ieu:
	$(SBT) "runMain riscvpipeline.GenerateIEU $(IEU_TARGET_DIR)"

generate-irom:
	$(SBT) "runMain riscvpipeline.GenerateIROM $(IMEM_DEPTH) $(IROM_TARGET_DIR) $(INSTRUCTION_INIT_FILE)"

generate-ifu:
	$(SBT) "runMain riscvpipeline.GenerateIFU $(IMEM_DEPTH) $(IFU_TARGET_DIR) $(INSTRUCTION_INIT_FILE) $(RESET_VECTOR)"

generate-lsu:
	$(SBT) "runMain riscvpipeline.GenerateLSU $(DMEM_DEPTH) $(LSU_TARGET_DIR)"

generate-cpu:
	$(SBT) "runMain riscvpipeline.GenerateRiscvPipeline $(IMEM_DEPTH) $(DMEM_DEPTH) $(CPU_TARGET_DIR) $(INSTRUCTION_INIT_FILE) $(RESET_VECTOR)"

test-pipeline:
	$(SBT) 'testOnly riscvpipeline.PipelineHazardSpec riscvpipeline.PipelineMemorySpec riscvpipeline.PipelineSmokeSpec'

generate-csr-access:
	$(SBT) "runMain riscvpipeline.GenerateCSRAccess $(CSR_ACCESS_TARGET_DIR)"

test-csr-access:
	$(SBT) 'testOnly riscvpipeline.privileged.CSRAccessSpec'

generate-priv-decode:
	$(SBT) "runMain riscvpipeline.GeneratePrivDecode $(PRIV_DECODE_TARGET_DIR) $(PRIV_DECODE_SUPERVISOR_SUPPORTED) $(PRIV_DECODE_VIRTUAL_MEMORY_SUPPORTED)"

test-priv-decode:
	$(SBT) 'testOnly riscvpipeline.privileged.PrivDecodeSpec'

generate-csr-status:
	$(SBT) "runMain riscvpipeline.GenerateCSRStatus $(CSR_STATUS_TARGET_DIR) $(CSR_STATUS_SUPERVISOR_SUPPORTED) $(CSR_STATUS_VIRTUAL_MEMORY_SUPPORTED) $(CSR_STATUS_BIG_ENDIAN_SUPPORTED)"

test-csr-status:
	$(SBT) 'testOnly riscvpipeline.privileged.CSRStatusSpec'

generate-csr-interrupt:
	$(SBT) "runMain riscvpipeline.GenerateCSRInterrupt $(CSR_INTERRUPT_TARGET_DIR) $(CSR_INTERRUPT_SUPERVISOR_SUPPORTED) $(CSR_INTERRUPT_SSTC_SUPPORTED)"

test-csr-interrupt:
	$(SBT) 'testOnly riscvpipeline.privileged.CSRInterruptSpec'

generate-csr-machine:
	$(SBT) "runMain riscvpipeline.GenerateCSRMachine $(CSR_MACHINE_TARGET_DIR) $(CSR_MACHINE_SUPERVISOR_SUPPORTED) $(CSR_MACHINE_VIRTUAL_MEMORY_SUPPORTED) $(CSR_MACHINE_SSTC_SUPPORTED) $(CSR_MACHINE_VECTORED_INTERRUPTS_SUPPORTED) $(CSR_MACHINE_HPM_COUNTERS) $(CSR_MACHINE_HART_ID)"

test-csr-machine:
	$(SBT) 'testOnly riscvpipeline.privileged.CSRMachineSpec'

generate-csr-supervisor:
	$(SBT) "runMain riscvpipeline.GenerateCSRSupervisor $(CSR_SUPERVISOR_TARGET_DIR) $(CSR_SUPERVISOR_SUPPORTED) $(CSR_SUPERVISOR_VIRTUAL_MEMORY_SUPPORTED) $(CSR_SUPERVISOR_SSTC_SUPPORTED) $(CSR_SUPERVISOR_VECTORED_INTERRUPTS_SUPPORTED) $(CSR_SUPERVISOR_HPM_COUNTERS) $(CSR_SUPERVISOR_ASID_BITS)"

test-csr-supervisor:
	$(SBT) 'testOnly riscvpipeline.privileged.CSRSupervisorSpec'

generate-csr-counters:
	$(SBT) "runMain riscvpipeline.GenerateCSRCounters $(CSR_COUNTERS_TARGET_DIR) $(CSR_COUNTERS_SUPERVISOR_SUPPORTED) $(CSR_COUNTERS_HPM_COUNTERS)"

test-csr-counters:
	$(SBT) 'testOnly riscvpipeline.privileged.CSRCountersSpec'

generate-priv-mode:
	$(SBT) "runMain riscvpipeline.GeneratePrivMode $(PRIV_MODE_TARGET_DIR) $(PRIV_MODE_SUPERVISOR_SUPPORTED)"

test-priv-mode:
	$(SBT) 'testOnly riscvpipeline.privileged.PrivModeSpec'

test:
	$(SBT) test

# 使用固定配置重新生成 RTL，避免新工程依赖旧导出文件。
generate-rtl-test:
	$(SBT) "runMain riscvpipeline.GenerateRiscvPipeline 64 64 generated/riscv-pipeline programs/riscvtest.memfile 0" \
	  "runMain riscvpipeline.GenerateRiscvPipeline 128 128 generated/riscv-pipeline128 programs/rv32-configtest.memfile 0x100" \
	  "runMain riscvpipeline.GenerateRiscvPipeline 128 64 generated/rv32-acceptance programs/rv32-acceptance.memfile 0" \
	  "runMain riscvpipeline.GenerateRiscvPipeline 256 128 generated/rv32-acceptance-expanded programs/rv32-acceptance-expanded.memfile 0x100"

test-rtl: generate-rtl-test
	VERILATOR="$(VERILATOR)" ./scripts/test-rtl.sh

clean:
	$(SBT) clean

.PHONY: generate-trap test-trap
generate-trap:
	$(SBT) "runMain riscvpipeline.GenerateTrap $(TRAP_TARGET_DIR) $(TRAP_SUPERVISOR_SUPPORTED)"

test-trap:
	$(SBT) 'testOnly riscvpipeline.privileged.TrapSpec'

.PHONY: generate-wfi test-wfi
generate-wfi:
	$(SBT) "runMain riscvpipeline.GenerateWFI $(WFI_TARGET_DIR) $(WFI_TIMEOUT_CYCLES)"

test-wfi:
	$(SBT) 'testOnly riscvpipeline.privileged.WFISpec'

.PHONY: generate-endian-swap test-endian-swap
generate-endian-swap:
	$(SBT) "runMain riscvpipeline.GenerateEndianSwap $(ENDIAN_SWAP_TARGET_DIR)"

test-endian-swap:
	$(SBT) 'testOnly riscvpipeline.privileged.EndianSwapSpec'

.PHONY: generate-csr test-csr
generate-csr:
	$(SBT) "runMain riscvpipeline.GenerateCSR $(CSR_TARGET_DIR) $(CSR_SUPERVISOR_SUPPORTED) $(CSR_BIG_ENDIAN_SUPPORTED) $(CSR_SSTC_SUPPORTED) $(CSR_HPM_COUNTERS)"

test-csr:
	$(SBT) 'testOnly riscvpipeline.privileged.CSRSpec'

PRIVILEGED_TARGET_DIR ?= generated/privileged
PRIVILEGED_SUPERVISOR_SUPPORTED ?= true
PRIVILEGED_BIG_ENDIAN_SUPPORTED ?= true
PRIVILEGED_SSTC_SUPPORTED ?= false
PRIVILEGED_HPM_COUNTERS ?= 0
PRIVILEGED_WFI_TIMEOUT_CYCLES ?= 65536
.PHONY: generate-privileged test-privileged
generate-privileged:
	$(SBT) "runMain riscvpipeline.GeneratePrivileged $(PRIVILEGED_TARGET_DIR) $(PRIVILEGED_SUPERVISOR_SUPPORTED) $(PRIVILEGED_BIG_ENDIAN_SUPPORTED) $(PRIVILEGED_SSTC_SUPPORTED) $(PRIVILEGED_HPM_COUNTERS) $(PRIVILEGED_WFI_TIMEOUT_CYCLES)"

test-privileged:
	$(SBT) 'testOnly riscvpipeline.privileged.PrivilegedSpec'
