
MEMORY {
    ramheader (r!wx) : ORIGIN = 0x0000, LENGTH = 1K
    ramnone (r!wx) : ORIGIN = 0x1000, LENGTH = 3K
    ramrx (rx!w) : ORIGIN = 0x2000, LENGTH = 4K
    ramrw (rw!x) : ORIGIN = 0x3000, LENGTH = 4K
}

PHDRS {
    headers PT_PHDR PHDRS;
    phdr    PT_LOAD FILEHDR PHDRS FLAGS(4);
    text    PT_LOAD FLAGS(5);
    data    PT_LOAD FLAGS(6);
    bss     PT_LOAD FLAGS(6);
    dynamic PT_DYNAMIC;
}

SECTIONS {

    .dynamic : {
        *(.dynamic)
    } > ramnone : phdr : dynamic

    .dynsym : {
        *(.dynsym)
    } > ramnone : phdr

    .dynstr : {
        *(.dynstr)
    } > ramnone : phdr

    .hash : {
        *(.hash)
    } > ramnone : phdr

    .gnu.hash : {
        *(.gnu.hash)
    } > ramnone : phdr

    .eh_frame : {
        *(.eh_frame)
    } > ramnone : phdr

    .eh_frame_hdr : {
        *(.eh_frame_hdr)
    } > ramnone : phdr

    .text : {
        . = ALIGN(4096);
        PROVIDE( __text_start = . );
        *(.text.init) *(.text .text.*)
        PROVIDE( __text_end = . );
    } > ramrx : text

    .rodata : {
        *(.rodata .rodata.*) *(.got.plt)
    } > ramrx : text

    .ARM.exidx : {
        *(.ARM.exidx)
    } > ramrx : text

    .data : {
        *(*.sdata .sdata*) *(.data .data.*)
    } > ramrw : data

    .bss : {
        *(.sbss .sbss.*) *(.bss .bss.*)
    } > ramrw : bss

}
