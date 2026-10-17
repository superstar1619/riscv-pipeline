#include "platform.h"
START
    la t0, trap_handler
    csrw mtvec, t0
    li t0, 0x80004000
    li t1, 0x11223344
    sw t1, 0(t0)
    /* MBE is mstatush[5] on RV32; fetch stays little endian. */
    li t1, 0x20
    csrw mstatush, t1
    lw t3, 0(t0)
    li t1, 0xaabbccdd
    sw t1, 4(t0)
    csrw mstatush, zero
    EMIT t3
    lw t3, 4(t0)
    EMIT t3
    ecall
    li t3, 0x99
    EMIT t3
    FINISH 5
.balign 4
trap_handler:
    csrr t3, mcause
    EMIT t3
    csrr t3, mtval
    EMIT t3
    csrr t4, mepc
    addi t4, t4, 4
    csrw mepc, t4
    mret
