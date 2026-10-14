// Platform only: the upstream test body and trap logger remain in WALLY-TEST-LIB-32.h.
#define RVTEST_ISA(...)
#define RVTEST_CASE(...)
#define RVMODEL_BOOT call init_data
#define RVTEST_CODE_BEGIN
#define RVTEST_CODE_END
#define RVMODEL_HALT j cvw_finish
#define RVTEST_DATA_BEGIN .data
#define RVTEST_DATA_END
#define RVMODEL_DATA_BEGIN .data
#define RVMODEL_DATA_END
.option norvc
.option norelax
.section .text.boot,"ax"
.globl _start
_start:
  j rvtest_entry_point
.section .text,"ax"
cvw_finish:
  // terminate_test has returned to M mode. Restore all data accesses to little endian.
  csrci mstatus, 8
  csrw mie, zero
  li t0, 0x20040
  csrc mstatus, t0
  li t0, 0x30
  csrc mstatush, t0
  li t0, 0xff20
  sw zero, 0(t0)
  la t0, test_1_res
  sub t2, a6, t0
  li t3, 0xff04
  sw t2, 0(t3)
  li t2, 1024
  li t3, 0xff00
1:
  lw t4, 0(t0)
  sw t4, 0(t3)
  addi t0, t0, 4
  addi t2, t2, -1
  bnez t2, 1b
  la t0, signature_guard
  lw t4, 0(t0)
  sw t4, 8(t3)
  la t0, sig_end_canary
  lw t4, 0(t0)
  sw t4, 12(t3)
  li t4, 0x5a
  sw t4, 16(t3)
2: j 2b
