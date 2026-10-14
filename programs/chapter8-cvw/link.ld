OUTPUT_ARCH(riscv)
ENTRY(_start)
MEMORY { IROM (rx) : ORIGIN = 0, LENGTH = 64K
         DTIM (rw) : ORIGIN = 0xc000, LENGTH = 0x3800 }
SECTIONS {
 .text : { *(.text.boot) *(.text.init) *(.text .text.*) *(.initdata) } > IROM
 .data : { __data_start = .; *(.data .data.*) *(.rodata .rodata.*) *(.signature); __data_end = .; } > DTIM
 .bss (NOLOAD) : { *(.bss .bss.*) *(COMMON) } > DTIM
 /DISCARD/ : { *(.comment) *(.riscv.attributes) }
 ASSERT(SIZEOF(.text) <= 0xc000, "IROM code overlaps DTIM address window")
 ASSERT(__data_end <= 0xf800, "DTIM data overlaps protocol registers")
 ASSERT((__data_end - __data_start) % 4 == 0, "unaligned initialization")
}
