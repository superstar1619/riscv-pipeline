/* CPU initializes all RAM; output uses real LW and ordinary committed stores. */
.option norvc
.option norelax
.macro START
.section .text.init,"ax"
.global _start
_start:
    li s0, 0x80008000
.endm
.macro EMIT reg
    sw \reg, 0(s0)
    addi s0, s0, 4
.endm
.macro FINISH count
    li s1, 0x80008000
    li s2, 0x8000ff00
    li s3, 0
    li s4, \count
.global readback_load
readback_load:
    lw a0, 0(s1)
    sw s3, 32(s2)
    sw a0, 36(s2)
    addi s1, s1, 4
    addi s3, s3, 1
    blt s3, s4, readback_load
    sw s4, 40(s2)
    li t0, 0x5a
    sw t0, 0(s2)
.global completion_loop
completion_loop:
    j completion_loop
.endm
