#include "syscall_ext.h"

// Raw Linux syscall ABI: return the kernel result, including -4095..-1 errors.
// There is no libc errno or TLS access here.
// Unwinding is unreliable on i386 and ARM32: syscalls repurpose ebp/r7, and this
// ELF-less shellcode has no registered unwind metadata to describe those changes.
__attribute__((noinline))
EXPORT uintptr_t syscall_ext
        (int number, uintptr_t arg1, uintptr_t arg2, uintptr_t arg3, uintptr_t arg4, uintptr_t arg5, uintptr_t arg6) {
#if defined(__x86_64__)
    register uintptr_t r10 __asm__("r10") = arg4;
    register uintptr_t r8 __asm__("r8") = arg5;
    register uintptr_t r9 __asm__("r9") = arg6;
    uintptr_t result;
    __asm__ __volatile__("syscall"
            : "=a"(result)
            : "0"((uintptr_t) number), "D"(arg1), "S"(arg2), "d"(arg3),
              "r"(r10), "r"(r8), "r"(r9)
            : "rcx", "r11", "cc", "memory");
    return result;
#elif defined(__i386__)
    // Preserve the frame pointer and PIC register while passing all six args.
    uintptr_t result;
    struct {
        uintptr_t arg1;
        uintptr_t arg6;
        uintptr_t number;
    } saved = {arg1, arg6, (uintptr_t) number};
    __asm__ __volatile__("push %%ebp\n"
                         "push %%ebx\n"
                         "movl 4(%1),%%ebp\n"
                         "movl 0(%1),%%ebx\n"
                         "movl 8(%1),%%eax\n"
                         "int $0x80\n"
                         "pop %%ebx\n"
                         "pop %%ebp"
            : "=a"(result)
            : "0"(&saved), "c"(arg2), "d"(arg3), "S"(arg4), "D"(arg5)
            : "cc", "memory");
    return result;
#elif defined(__aarch64__)
    register uintptr_t x0 __asm__("x0") = arg1;
    register uintptr_t x1 __asm__("x1") = arg2;
    register uintptr_t x2 __asm__("x2") = arg3;
    register uintptr_t x3 __asm__("x3") = arg4;
    register uintptr_t x4 __asm__("x4") = arg5;
    register uintptr_t x5 __asm__("x5") = arg6;
    register uintptr_t x8 __asm__("x8") = (uintptr_t) number;
    __asm__ __volatile__("svc 0"
            : "+r"(x0)
            : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5), "r"(x8)
            : "cc", "memory");
    return x0;
#elif defined(__ARM_EABI__)
    register uintptr_t r0 __asm__("r0") = arg1;
    register uintptr_t r1 __asm__("r1") = arg2;
    register uintptr_t r2 __asm__("r2") = arg3;
    register uintptr_t r3 __asm__("r3") = arg4;
    register uintptr_t r4 __asm__("r4") = arg5;
    register uintptr_t r5 __asm__("r5") = arg6;
    // Save r7 so the wrapper also works when it is used as the frame pointer.
    __asm__ __volatile__("push {r7}\n"
                         "mov r7, %1\n"
                         "svc 0\n"
                         "pop {r7}"
            : "+r"(r0)
            : "r"((uintptr_t) number), "r"(r1), "r"(r2), "r"(r3), "r"(r4), "r"(r5)
            : "lr", "cc", "memory");
    return r0;
#elif defined(__riscv) && __riscv_xlen == 64
    register uintptr_t a0 __asm__("a0") = arg1;
    register uintptr_t a1 __asm__("a1") = arg2;
    register uintptr_t a2 __asm__("a2") = arg3;
    register uintptr_t a3 __asm__("a3") = arg4;
    register uintptr_t a4 __asm__("a4") = arg5;
    register uintptr_t a5 __asm__("a5") = arg6;
    register uintptr_t a7 __asm__("a7") = (uintptr_t) number;
    __asm__ __volatile__("ecall"
            : "+r"(a0)
            : "r"(a1), "r"(a2), "r"(a3), "r"(a4), "r"(a5), "r"(a7)
            : "memory");
    return a0;
#else
#error "Unsupported architecture"
#endif
}
