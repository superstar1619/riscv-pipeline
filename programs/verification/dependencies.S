.option norvc
.option norelax
.section .text
.global verification_entry
verification_entry:
 la s0, begin_signature
 la s1, scratch
 # Gap 0: ALU, branch, JALR, store address and store data
 addi t0,zero,41
 addi t1,t0,1
 sw t1,0(s0)
 addi s0,s0,4
 addi t0,zero,1
 bne t0,zero,branch_0
 sw zero,0(s0)
branch_0:
 sw t0,0(s0)
 addi s0,s0,4
 la t0,jump_0
 jalr t1,0(t0)
 sw zero,0(s0)
jump_0:
 sw t1,0(s0)
 addi s0,s0,4
 addi t0,s1,0
 sw t1,0(t0)
 addi t0,zero,42
 sw t0,0(s1)
 lw t1,0(s1)
 addi t1,t1,1
 sw t1,0(s0)
 addi s0,s0,4
 # Gap 1: ALU, branch, JALR, store address and store data
 addi t0,zero,41
 nop
 addi t1,t0,1
 sw t1,0(s0)
 addi s0,s0,4
 addi t0,zero,1
 nop
 bne t0,zero,branch_1
 sw zero,0(s0)
branch_1:
 sw t0,0(s0)
 addi s0,s0,4
 la t0,jump_1
 nop
 jalr t1,0(t0)
 sw zero,0(s0)
jump_1:
 sw t1,0(s0)
 addi s0,s0,4
 addi t0,s1,0
 nop
 sw t1,0(t0)
 addi t0,zero,42
 nop
 sw t0,0(s1)
 lw t1,0(s1)
 addi t1,t1,1
 sw t1,0(s0)
 addi s0,s0,4
 # Gap 2: ALU, branch, JALR, store address and store data
 addi t0,zero,41
 nop
 nop
 addi t1,t0,1
 sw t1,0(s0)
 addi s0,s0,4
 addi t0,zero,1
 nop
 nop
 bne t0,zero,branch_2
 sw zero,0(s0)
branch_2:
 sw t0,0(s0)
 addi s0,s0,4
 la t0,jump_2
 nop
 nop
 jalr t1,0(t0)
 sw zero,0(s0)
jump_2:
 sw t1,0(s0)
 addi s0,s0,4
 addi t0,s1,0
 nop
 nop
 sw t1,0(t0)
 addi t0,zero,42
 nop
 nop
 sw t0,0(s1)
 lw t1,0(s1)
 addi t1,t1,1
 sw t1,0(s0)
 addi s0,s0,4
 # M/W both match: M wins; x0 never forwards
 addi t0,zero,1
 addi t0,zero,2
 add t1,t0,t0
 sw t1,0(s0)
 addi s0,s0,4
 addi zero,t1,7
 add t1,zero,zero
 sw t1,0(s0)
 addi s0,s0,4
 bne zero,zero,failed
 li t0,0x89abcdef
 sb t0,0(s1)
 lb t1,0(s1)
 sw t1,0(s0)
 addi s0,s0,4
 li t0,0x89abcdef
 sb t0,1(s1)
 lb t1,1(s1)
 sw t1,0(s0)
 addi s0,s0,4
 li t0,0x89abcdef
 sb t0,2(s1)
 lb t1,2(s1)
 sw t1,0(s0)
 addi s0,s0,4
 li t0,0x89abcdef
 sb t0,3(s1)
 lb t1,3(s1)
 sw t1,0(s0)
 addi s0,s0,4
 li t0,0x89abcdef
 sh t0,0(s1)
 lh t1,0(s1)
 sw t1,0(s0)
 addi s0,s0,4
 li t0,0x89abcdef
 sh t0,2(s1)
 lh t1,2(s1)
 sw t1,0(s0)
 addi s0,s0,4
 li t0,0x89abcdef
 sw t0,0(s1)
 lw t1,0(s1)
 sw t1,0(s0)
 addi s0,s0,4
 li a0,1
 j validation_finish
failed:
 li a0,3
 j validation_finish
.section .data
.balign 4
.global begin_signature
begin_signature:
 .fill 32,4,0
.global end_signature
end_signature:
scratch:
 .word 0
