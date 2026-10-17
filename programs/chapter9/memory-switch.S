#include "platform.h"
START
    li t0, 0x80004000
    li t1, 0x807f00ff
    sw t1, 0(t0)
    lw t3, 0(t0)
    EMIT t3
    lb t3, 3(t0)
    EMIT t3
    lbu t3, 3(t0)
    EMIT t3
    lh t3, 2(t0)
    EMIT t3
    lhu t3, 2(t0)
    EMIT t3
    lb t3, 1(t0)
    EMIT t3
    li t1, 0x80
    sb t1, 1(t0)
    lb t3, 1(t0)
    EMIT t3
    li t1, 0x1234
    sh t1, 2(t0)
    lw t3, 0(t0)
    EMIT t3
    /* Executable RAM initialized by actual CPU stores, then synchronized. */
    li t0, 0x80000100
    li t1, 0x02a00593 /* addi a1,zero,42 */
    sw t1, 0(t0)
    li t1, 0x0002ae03 /* lw t3,0(t0): data request during RAM fetch */
    sw t1, 4(t0)
    li t1, 0x00058593 /* addi a1,a1,0 */
    sw t1, 8(t0)
    li t1, 0x00008067 /* jalr zero,ra,0 */
    sw t1, 12(t0)
    fence.i
    jalr ra, t0, 0
    EMIT a1
    li t3, 0x13579bdf
    EMIT t3
#if MIXED
    /* Separate local DTIM access between bus fetches and RAM accesses. */
    li t0, 0x800
    li t1, 0x55aa33cc
    sw t1, 0(t0)
    lw t3, 0(t0)
    EMIT t3
    FINISH 11
#else
    FINISH 10
#endif
