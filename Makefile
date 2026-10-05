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

.PHONY: generate generate-cmp generate-shifter generate-swbytemask \
  generate-subwordwrite generate-subwordread generate-dtim generate-alu \
  generate-regfile generate-controller generate-datapath generate-ieu \
  generate-irom generate-ifu generate-lsu generate-cpu test test-pipeline test-rtl clean

.PHONY: build-acceptance generate-acceptance test-acceptance generate-rtl-test

build-acceptance:
	python3 scripts/build-rv32-acceptance.py

generate-acceptance:
	$(SBT) "runMain riscvpipeline.GenerateRiscvPipeline 128 64 generated/rv32-acceptance programs/rv32-acceptance.memfile 0" "runMain riscvpipeline.GenerateRiscvPipeline 256 128 generated/rv32-acceptance-expanded programs/rv32-acceptance-expanded.memfile 0x100"

test-acceptance:
	@echo "Single-cycle acceptance traces require pipeline adaptation; use test-pipeline." >&2
	@exit 2

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

test:
	$(SBT) test

# 使用固定配置重新生成 RTL，避免新工程依赖旧导出文件。
generate-rtl-test:
	$(SBT) "runMain riscvpipeline.GenerateRiscvPipeline 64 64 generated/riscv-pipeline programs/riscvtest.memfile 0" \
	  "runMain riscvpipeline.GenerateRiscvPipeline 128 128 generated/riscv-pipeline128 programs/rv32-configtest.memfile 0x100" \
	  "runMain riscvpipeline.GenerateRiscvPipeline 128 64 generated/rv32-acceptance programs/rv32-acceptance.memfile 0" \
	  "runMain riscvpipeline.GenerateRiscvPipeline 256 128 generated/rv32-acceptance-expanded programs/rv32-acceptance-expanded.memfile 0x100"

test-rtl:
	VERILATOR="$(VERILATOR)" ./scripts/test-rtl.sh

clean:
	$(SBT) clean
