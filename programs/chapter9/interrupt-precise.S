#include "platform.h"
START
    la t0, trap_handler
    csrw mtvec, t0
    li t0, 8
    csrw mie, t0
    li t0, 0x80004000
    li t1, 0x2468ace0
    sw t1, 0(t0)
    /* Arm deterministic IRQ drive at a real accepted bus transaction. */
    li t2, 0x8000fe00
    li t1, 1
    sw t1, 0(t2)
    lw t3, 0(t0)
    /* Pending IRQ cannot preempt before this global-enable instruction. */
    csrsi mstatus, 8
.global irq_resume
irq_resume:
    li t3, 0x99
    EMIT t3
    FINISH 4
.balign 4
trap_handler:
    csrr t3, mcause
    EMIT t3
    csrr t3, mepc
    EMIT t3
    csrr t3, mtval
    EMIT t3
    mret
