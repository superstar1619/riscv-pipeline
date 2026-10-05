.option norvc
.option norelax
.section .text.start,"ax",@progbits
.global _start
_start:
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
  li t0, 3
  li t1, 0x8021ff00
  sw t0, 0(t1)
3:
  j 3b
