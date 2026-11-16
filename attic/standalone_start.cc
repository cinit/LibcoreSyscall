#include <cstdint>
#include <array>
#include <type_traits>

#include <unistd.h>
#include <linux/auxvec.h>
#include <sys/mman.h>
#include <elf.h>
#include <fcntl.h>

#include "common_macros.h"
#include "syscall_wrapper.h"
#include "syscall_ext.h"
#include "kernel_abi.h"
#include "hook_info.h"
#include "ashmem_utils.h"

void init_hook_info() {
    uint64_t pageSize = 0;
    // get page size by auxv
    int fd = lsw_openat(AT_FDCWD, "/proc/self/auxv", O_RDONLY, 0);
    // fina page size
    using ElfXX_auxv_t = std::conditional_t<sizeof(void*) == 8, Elf64_auxv_t, Elf32_auxv_t>;
    while (true) {
        ElfXX_auxv_t aux = {};
        ssize_t r = lsw_read(fd, &aux, sizeof(aux));
        if (is_error(r)) {
            break;
        }
        if (r == 0) {
            break;
        }
        if (r != sizeof(aux)) {
            __builtin_trap();
        }
        if (aux.a_type == AT_PAGESZ) {
            pageSize = aux.a_un.a_val;
            break;
        }
    }
    lsw_close(fd);
    if (pageSize == 0) {
        __builtin_trap();
    }
    auto* info = const_cast<HookInfo*>(get_hook_info());
    // make .text page writable
    void* pageStart = align_down((void*) info, pageSize);
    int rc = lsw_mprotect(pageStart, pageSize, PROT_READ | PROT_WRITE | PROT_EXEC);
    assert_syscall_success(rc);
    info->page_size = pageSize;
    kernel_stat64_compat ashmemStat = {};
    if (lsw_stat64("/dev/ashmem", &ashmemStat) == 0) {
        info->ashmem_dev_v = ashmemStat.st_dev;
    }
    // make .text page readonly
    rc = lsw_mprotect(pageStart, pageSize, PROT_READ | PROT_EXEC);
    assert_syscall_success(rc);
}


int main(int argc, char** argv) {
    // init hook info
    init_hook_info();
    int nonExistentFd = 123;
    auto size = ashmem_dev_get_size_region(nonExistentFd);
    lsw_write(STDOUT_FILENO, &size, sizeof(size));
}

extern "C" [[noreturn]]
void _nocrt_call_main(int argc, char** argv) {
    // call main
    int rc = main(argc, argv);
    // exit
    lsw_exit_group(rc);
}

extern "C" [[noreturn]]
void _nocrt_start_c(long* p) {
    int argc = (int) p[0];
    char** argv = (char**) (p + 1);
    _nocrt_call_main(argc, argv);
}

EXPORT [[noreturn]] __attribute__((naked))
void _start() {
    // from musl
#if defined(__x86_64__)
    __asm__(
            "	xor %rbp,%rbp \n"
            "	mov %rsp,%rdi \n"
            ".weak _DYNAMIC \n"
            ".hidden _DYNAMIC \n"
            "	lea _DYNAMIC(%rip),%rsi \n"
            "	andq $-16,%rsp \n"
            "	call _nocrt_call_main\n"
            );
#elif defined(__i386__)
    __asm__(
            ".weak _DYNAMIC \n"
            ".hidden _DYNAMIC \n"
            "	xor %ebp,%ebp \n"
            "	mov %esp,%eax \n"
            "	and $-16,%esp \n"
            "	push %eax \n"
            "	push %eax \n"
            "	call 1f \n"
            "1:	addl $_DYNAMIC-1b,(%esp) \n"
            "	push %eax \n"
             "	call _nocrt_call_main\n"
            );
#elif defined(__aarch64__)
    __asm__(
            "	mov x29, #0\n"
            "	mov x30, #0\n"
            "	mov x0, sp\n"
            ".weak _DYNAMIC\n"
            ".hidden _DYNAMIC\n"
            "	adrp x1, _DYNAMIC\n"
            "	add x1, x1, #:lo12:_DYNAMIC\n"
            "	and sp, x0, #-16\n"
            "	b _nocrt_call_main\n"
            );
#elif defined(__arm__)
    __asm__(
            "	mov fp, #0 \n"
            "	mov lr, #0 \n"
            "	ldr a2, 1f \n"
            "	add a2, pc, a2 \n"
            "	mov a1, sp \n"
            "2:	and ip, a1, #-16 \n"
            "	mov sp, ip \n"
            "	bl _nocrt_call_main \n"
            ".weak _DYNAMIC \n"
            ".hidden _DYNAMIC \n"
            ".align 2 \n"
            "1:	.word _DYNAMIC-2b \n"
            );
#elif defined(__riscv) && __riscv_xlen == 64
    __asm__(
            ".weak __global_pointer$\n"
            ".hidden __global_pointer$\n"
            ".option push\n"
            ".option norelax\n\t"
            "lla gp, __global_pointer$\n"
            ".option pop\n\t"
            "mv a0, sp\n"
            ".weak _DYNAMIC\n"
            ".hidden _DYNAMIC\n\t"
            "lla a1, _DYNAMIC\n\t"
            "andi sp, sp, -16\n\t"
            "tail _nocrt_call_main"
            );
#else
#error "Unsupported architecture"
#endif
}
