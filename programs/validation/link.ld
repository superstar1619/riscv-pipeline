OUTPUT_ARCH(riscv)
ENTRY(_start)
SECTIONS {
  . = 0x80000000;
  .text : { KEEP(*(.text.start)) *(.text .text.*) }
  .initdata : { *(.initdata) }
  ASSERT(. <= 0x80200000, "Code exceeds 2 MiB IROM")
  . = 0x80210000;
  .data : ALIGN(16) {
    __data_start = .;
    *(.rodata .rodata.* .srodata .srodata.*)
    *(.data .data.* .sdata .sdata.*)
    __data_end = .;
  }
  .bss : ALIGN(16) {
    __bss_start = .;
    *(.bss .bss.* .sbss .sbss.* COMMON)
    . = ALIGN(4);
    __bss_end = .;
  }
  ASSERT(. < 0x8021c000, "Data leaves insufficient stack space")
  __stack_top = 0x8021f000;
  .tohost 0x8021ff00 : { tohost = .; QUAD(0); fromhost = .; QUAD(0); }
  .benchmark_output 0x8021ff10 (NOLOAD) : { __benchmark_output = .; . += 4; }
  .signature_output 0x8021ff20 (NOLOAD) : { __signature_output = .; . += 12; }
  ASSERT(__stack_top < ADDR(.tohost), "Stack overlaps completion protocol")
  ASSERT(ADDR(.signature_output) + SIZEOF(.signature_output) <= 0x80220000,
         "Signature protocol exceeds DTIM")
  /DISCARD/ : { *(.comment .riscv.attributes .eh_frame*) }
}
