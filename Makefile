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
CPU_TARGET_DIR ?= generated/riscv-single

.PHONY: generate generate-cmp generate-shifter generate-swbytemask \
  generate-subwordwrite generate-subwordread generate-dtim generate-alu \
  generate-regfile generate-controller generate-datapath generate-ieu \
  generate-irom generate-ifu generate-lsu generate-cpu test test-rtl clean

.PHONY: build-acceptance generate-acceptance test-acceptance generate-rtl-test

build-acceptance:
	python3 scripts/build-rv32-acceptance.py

generate-acceptance:
	$(SBT) "runMain riscvsingle.GenerateRiscvSingle 128 64 generated/rv32-acceptance programs/rv32-acceptance.memfile 0" "runMain riscvsingle.GenerateRiscvSingle 256 128 generated/rv32-acceptance-expanded programs/rv32-acceptance-expanded.memfile 0x100"

test-acceptance:
	$(SBT) 'testOnly riscvsingle.CpuAcceptanceSpec riscvsingle.CpuResetSpec'

generate:
	$(SBT) "runMain riscvsingle.GenerateExtend $(WIDTH) $(TARGET_DIR)"

generate-cmp:
	$(SBT) "runMain riscvsingle.GenerateCmp $(WIDTH) $(CMP_TARGET_DIR)"

generate-shifter:
	$(SBT) "runMain riscvsingle.GenerateShifter $(WIDTH) $(SHIFTER_TARGET_DIR)"

generate-swbytemask:
	$(SBT) "runMain riscvsingle.GenerateSwByteMask $(WIDTH) $(SWBYTEMASK_TARGET_DIR)"

generate-subwordwrite:
	$(SBT) "runMain riscvsingle.GenerateSubwordWrite $(WIDTH) $(SUBWORDWRITE_TARGET_DIR)"

generate-subwordread:
	$(SBT) "runMain riscvsingle.GenerateSubwordRead $(WIDTH) $(SUBWORDREAD_TARGET_DIR)"

generate-dtim:
	$(SBT) "runMain riscvsingle.GenerateDTIM $(WIDTH) $(DMEM_DEPTH) $(DTIM_TARGET_DIR)"

generate-alu:
	$(SBT) "runMain riscvsingle.GenerateALU $(WIDTH) $(ALU_TARGET_DIR)"

generate-regfile:
	$(SBT) "runMain riscvsingle.GenerateRegFile $(WIDTH) $(REGFILE_TARGET_DIR) $(REGISTER_COUNT)"

generate-controller:
	$(SBT) "runMain riscvsingle.GenerateController $(CONTROLLER_TARGET_DIR)"

generate-datapath:
	$(SBT) "runMain riscvsingle.GenerateDatapath $(DATAPATH_TARGET_DIR)"

generate-ieu:
	$(SBT) "runMain riscvsingle.GenerateIEU $(IEU_TARGET_DIR)"

generate-irom:
	$(SBT) "runMain riscvsingle.GenerateIROM $(IMEM_DEPTH) $(IROM_TARGET_DIR) $(INSTRUCTION_INIT_FILE)"

generate-ifu:
	$(SBT) "runMain riscvsingle.GenerateIFU $(IMEM_DEPTH) $(IFU_TARGET_DIR) $(INSTRUCTION_INIT_FILE) $(RESET_VECTOR)"

generate-lsu:
	$(SBT) "runMain riscvsingle.GenerateLSU $(DMEM_DEPTH) $(LSU_TARGET_DIR)"

generate-cpu:
	$(SBT) "runMain riscvsingle.GenerateRiscvSingle $(IMEM_DEPTH) $(DMEM_DEPTH) $(CPU_TARGET_DIR) $(INSTRUCTION_INIT_FILE) $(RESET_VECTOR)"

test:
	$(SBT) test

# 使用固定配置重新生成 RTL，避免新工程依赖旧导出文件。
generate-rtl-test:
	$(SBT) "runMain riscvsingle.GenerateRiscvSingle 64 64 generated/riscv-single programs/riscvtest.memfile 0" \
	  "runMain riscvsingle.GenerateRiscvSingle 128 128 generated/riscv-single128 programs/rv32-configtest.memfile 0x100" \
	  "runMain riscvsingle.GenerateRiscvSingle 128 64 generated/rv32-acceptance programs/rv32-acceptance.memfile 0" \
	  "runMain riscvsingle.GenerateRiscvSingle 256 128 generated/rv32-acceptance-expanded programs/rv32-acceptance-expanded.memfile 0x100"

test-rtl: generate-rtl-test
	VERILATOR="$(VERILATOR)" ./scripts/test-rtl.sh

clean:
	$(SBT) clean
