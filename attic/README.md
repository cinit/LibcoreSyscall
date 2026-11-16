# About Shellcode

Here are some explanations of the shellcode used in the library.

Note that all the shellcode has been already compiled and based64-embedded into the Java code,
there is no need to compile the shellcode again if everything works fine.

All these files in this `attic` directory are just for reference purposes.

## Make the text section page aligned

The `.text` section had better be page aligned, in case there are some adrp+add instructions in the shellcode.

```c
// add this to the beginning of the C file to align sections
__asm__(".if 0\n.endif"); /* ICC fix */
__asm__(".text\n.p2align 12,0,4095\n___text_section: .globl ___text_section");
__asm__(".data\n.p2align 12,0,4095\n___data_section: .globl ___data_section");
__asm__(".rodata\n.p2align 12,0,4095\n___rodata_section: .globl ___rodata_section");
```

Since there should be no `.data`/`.bss`/`.rodata`/`.got`/`.plt` sections in the shellcode,
the only section which needs to be page aligned is the `.text` section.

Note that this line should be placed at the beginning of the C file, before any other code.

## JNI Wrappers

The JNI wrappers are just some simple function calls to the native functions.

Let's have a look at `NativeBridge_nativeCallPointerFunction4`,
all the arguments and the return value are `jlong` which is a 64-bit integer in JNI.
And they are casted to `void*` in the native function.

Any integer types or pointer which have the same or smaller size as `void*` can be passed as arguments into the
function,
and any integer types which has the same or smaller size as `void*` as well as pointer/void can be used as the return
value.
This is guaranteed by the x86-sysv/amd64/aapcs32/aapcs64/riscv64 calling conventions.

See [jni_wrapper.cc](jni_wrapper.cc).

## Syscall

Thanks to the linux-syscall-support library, things are much easier now.

But keep in mind that shellcode has no libc, so there is neither Thread-Local-Storage nor `errno`.

See [syscall_ext.cc](syscall_ext.cc).

## Clear Cache

While the `__clear_cache` function is used without libc, a platform-specific implementation is needed.

See [clear_cache.cc](clear_cache.cc).

## Readonly Data

To make things easier, the shellcode should only have a `.text` section,
and no `.data`/`.bss`/`.rodata`/`.got`/`.plt` sections.
The readonly data in the `.text` section can be populated from Java side at runtime.

See [hook_info.cc](hook_info.cc).

## Linker Hook

It obvious that the ashmem neither supports mmap in `MAP_PRIVATE` mode nor shows its size in the `fstat64` syscall.

However, the linker does need them to load the shared object from ashmem.

It's a terrible idea to hook the linker, but it is the only simple way to load the shared object from ashmem.

See [fake_fstat64.cc](fake_fstat64.cc) and [fake_mmap64.cc](fake_mmap64.cc).

## Linker Complaint about Missing Symbols

Linker may complain about missing symbols, we can just copy them from the musl libc.

See [libc_support.cc](libc_support.cc).

## Compile the Shellcode

Some compilers/linker specific flags are needed to compile the shellcode.

- Symbolic linking, do not use GOT/PLT: `-Wl,-Bsymbolic`
- No libc: `-nostdlib`
- Disable RTTI and exceptions: `-fno-rtti -fno-exceptions`
- Shared Object: `-shared -fPIC`
- Visibility: `-fvisibility=hidden -fvisibility-inlines-hidden`
- Make linker complain about missing symbols: `-Wl,--no-allow-shlib-undefined,--no-undefined`
- Disable lazy binding: `-Wl,-z,defs,-z,now,-z,relro`
- Specify the linker script: `-T,shellcode.ld`
- Remove unused code: `-Wl,--gc-sections`

An example command to compile the shellcode:

```shell
/path/to/clang++ -shared -fPIC -std=c++14 -O3 \
-fvisibility=hidden -fvisibility-inlines-hidden -fno-omit-frame-pointer -Wall \
-fno-rtti -fno-exceptions -nostdlib \
-Wl,-Bsymbolic,--no-allow-shlib-undefined,--no-undefined,-z,defs,-z,now,-z,relro,--gc-sections,-T,shellcode.ld \
-I/path/to/jni/include -I/path/to/linux-syscall-support \
all_in_one.cc -o libcore_syscall.so
```

The `readelf --dynamic *.so` output may look like this:

```text
Dynamic section at offset 0x1000 contains 9 entries:
  Tag        Type                         Name/Value
 0x000000000000001e (FLAGS)              SYMBOLIC BIND_NOW
 0x000000006ffffffb (FLAGS_1)            Flags: NOW
 0x0000000000000006 (SYMTAB)             0x1090
 0x000000000000000b (SYMENT)             24 (bytes)
 0x0000000000000005 (STRTAB)             0x1258
 0x000000000000000a (STRSZ)              444 (bytes)
 0x000000006ffffef5 (GNU_HASH)           0x14b8
 0x0000000000000004 (HASH)               0x1414
 0x0000000000000000 (NULL)               0x0
```

Note that there should be no `DT_NEEDED` entry in the dynamic section.

Get the symbol table: `llvm-objdump -T libcore_syscall.so`

If you are using your own linker script, make sure that the `.text` and `.rodata` sections are in the right place.

Dump the .text and .rodata sections:
`llvm-objcopy -O binary --only-section=.text --only-section=.rodata libcore_syscall.so shellcode.bin`

That's all for the shellcode.
