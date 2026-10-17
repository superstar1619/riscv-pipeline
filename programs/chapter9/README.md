# Chapter 9 CPU-driven programs

All programs use RV32I, with Zicsr/Zifencei only where explicitly required. The
builder links the same source at reset 0x1000 (bus-only) or 0 (mixed local TIM).
Both instruction images are exactly 4 KiB. RAM is never preloaded; instructions,
data, signatures and protocol values are written by the executing CPU.

`memory-switch` checks signed/unsigned subword loads, byte/halfword stores,
load/store dependencies, RAM code installed by CPU stores followed by FENCE.I,
ROM→RAM→ROM execution, and mixed-mode local DTIM. `apb-subword` checks masked
scratch writes and the exact count of three successful APB writes.
`privilege-endian` checks MBE data reversal and machine ECALL cause/tval/return.
These MMIO/privilege programs have explicit independent expected values; they
are not claimed to be complete Spike differential tests.

Signature words live at 0x80008000. The CPU performs ordinary LW readback and
emits index/value stores to 0x8000ff20/24, then length to 0x8000ff28 and done=0x5a
to 0x8000ff00. The checker requires every index, actual LW retirement, exact
ordered stores, all signature words, and eight cycles with no later store.
