.option norvc
.option norelax
.section .text.start,"ax",@progbits
.global _start
_start:
  # Common deterministic architectural state for Spike and RTL.
  li x1, 0
  li x2, 0
  li x3, 0
  li x4, 0
  li x5, 0
  li x6, 0
  li x7, 0
  li x8, 0
  li x9, 0
  li x10, 0
  li x11, 0
  li x12, 0
  li x13, 0
  li x14, 0
  li x15, 0
  li x16, 0
  li x17, 0
  li x18, 0
  li x19, 0
  li x20, 0
  li x21, 0
  li x22, 0
  li x23, 0
  li x24, 0
  li x25, 0
  li x26, 0
  li x27, 0
  li x28, 0
  li x29, 0
  li x30, 0
  li x31, 0
  la sp, __stack_top
  call init_data
  la t0, __bss_start
  la t1, __bss_end
1:
  bgeu t0, t1, 2f
  sw zero, 0(t0)
  addi t0, t0, 4
  j 1b
2:
  call TEST_ENTRY
  li a0, 3
  j validation_finish

# Non-returning common exit. Every reported value is read by the CPU with LW.
# a0 carries the status; t0..t5 are disposable after the test has finished.
.section .text.validation_finish,"ax",@progbits
.global validation_finish
validation_finish:
  la t0, begin_signature
  la t1, end_signature
  sub t2, t1, t0
  srli t2, t2, 2
  la t3, __signature_output
  sw t2, 8(t3)
  li t2, 0
4:
  bgeu t0, t1, 5f
  lw t4, 0(t0)
  sw t2, 0(t3)
  sw t4, 4(t3)
  addi t0, t0, 4
  addi t2, t2, 1
  j 4b
5:
  la t3, tohost
  sw a0, 0(t3)
6:
  j 6b
