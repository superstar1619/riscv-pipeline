#include "platform.h"
START
    li t0, 0x10000000
    li t1, 0x11223344
    sw t1, 0(t0)
    lw t3, 0(t0)
    EMIT t3
    li t1, 0xaa
    sb t1, 1(t0)
    lw t3, 0(t0)
    EMIT t3
    li t1, 0xbbcc
    sh t1, 2(t0)
    lw t3, 0(t0)
    EMIT t3
    /* Read-only counter reveals repeated APB Access side effects. */
    lw t3, 4(t0)
    EMIT t3
    FINISH 4
