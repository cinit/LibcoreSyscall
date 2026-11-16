#include "syscall_ext.h"

#include <cstdint>

#include "lss_wrapper.h"

// remove the errno, we do not have TLS, return -errno if error
#undef  LSS_RETURN
#if defined(__i386__) || defined(__x86_64__) || defined(__ARM_ARCH_3__) \
 || defined(__ARM_EABI__) || defined(__aarch64__) || defined(__s390__) \
 || defined(__e2k__) || defined(__riscv) || defined(__loongarch_lp64)
/* Failing system calls return a negative result in the range of
 * -1..-4095. These are "errno" values with the sign inverted.
 */
#define LSS_RETURN(type, res)                                               \
    do {                                                                      \
      return (type) (res);                                                    \
    } while (0)
#endif
// we do not support powerpc

#if defined(__x86_64__)
#undef  _LSS_RETURN
#define _LSS_RETURN(type, res, cast)                                      \
      do {                                                                    \
        return (type)(cast)(res);                                             \
      } while (0)
#undef  LSS_RETURN
#define LSS_RETURN(type, res) _LSS_RETURN(type, res, uintptr_t)
#endif


__attribute__((noinline))
EXPORT uintptr_t syscall_ext
        (int number, uintptr_t arg1, uintptr_t arg2, uintptr_t arg3, uintptr_t arg4, uintptr_t arg5, uintptr_t arg6) {
    auto nr = (uintptr_t) number;
    uintptr_t __NR_name = nr;
#if defined(__x86_64__)
    LSS_BODY(6, uintptr_t, name, LSS_SYSCALL_ARG(arg1), LSS_SYSCALL_ARG(arg2),
             LSS_SYSCALL_ARG(arg3), LSS_SYSCALL_ARG(arg4),
             LSS_SYSCALL_ARG(arg5), LSS_SYSCALL_ARG(arg6));
#elif defined(__i386__)
    long __res;
    struct {
        long __a1;
        long __a6;
        long __nr;
    } __s = {(long) arg1, (long) arg6, (long) __NR_name};
    __asm__ __volatile__("push %%ebp\n"
                         "push %%ebx\n"
                         "movl 4(%1),%%ebp\n"
                         "movl 0(%1),%%ebx\n"
                         "movl 8(%1),%%eax\n"
                         LSS_ENTRYPOINT
                         "pop  %%ebx\n"
                         "pop  %%ebp"
            : "=a" (__res)
            :
            "0" ((long) (&__s)),
            "c" ((long) (arg2)), "d" ((long) (arg3)),
            "S" ((long) (arg4)), "D" ((long) (arg5))
            : "memory");
    LSS_RETURN(uintptr_t, __res);
#elif defined(__aarch64__)

#undef  LSS_BODY
#define LSS_BODY(type,name,args...)                                       \
          register int64_t __res_x0 __asm__("x0");                            \
          int64_t __res;                                                      \
          __asm__ __volatile__ ("mov x8, %1\n"                                \
                                "svc 0x0\n"                                   \
                                : "=r"(__res_x0)                              \
                                : "r"(__NR_##name) , ## args                  \
                                : "x8", "memory");                            \
          __res = __res_x0;                                                   \
          LSS_RETURN(type, __res)

    LSS_REG(0, arg1); LSS_REG(1, arg2); LSS_REG(2, arg3);
    LSS_REG(3, arg4); LSS_REG(4, arg5); LSS_REG(5, arg6);
    LSS_BODY(jlong, name, "r"(__r0), "r"(__r1), "r"(__r2), "r"(__r3),
                         "r"(__r4), "r"(__r5));
#elif defined(__ARM_EABI__)

#undef  LSS_BODY
#define LSS_BODY(type,name,args...)                                       \
          register long __res_r0 __asm__("r0");                               \
          long __res;                                                         \
          __asm__ __volatile__ ("push {r7}\n"                                 \
                                "mov r7, %1\n"                                \
                                "swi 0x0\n"                                   \
                                "pop {r7}\n"                                  \
                                : "=r"(__res_r0)                              \
                                : "r"(__NR_##name) , ## args                  \
                                : "lr", "memory");                            \
          __res = __res_r0;                                                   \
          LSS_RETURN(type, __res)


    LSS_REG(0, arg1); LSS_REG(1, arg2); LSS_REG(2, arg3);
    LSS_REG(3, arg4); LSS_REG(4, arg5); LSS_REG(5, arg6);
    LSS_BODY(jlong, name, "r"(__r0), "r"(__r1), "r"(__r2), "r"(__r3),
                         "r"(__r4), "r"(__r5));
#elif defined(__ARM_ARCH_3__)
LSS_REG(0, arg1); LSS_REG(1, arg2); LSS_REG(2, arg3);
    LSS_REG(3, arg4); LSS_REG(4, arg5); LSS_REG(5, arg6);
    LSS_BODY(jlong, name, "r"(__r0), "r"(__r1), "r"(__r2), "r"(__r3),
                         "r"(__r4), "r"(__r5));
#elif defined(__riscv) && __riscv_xlen == 64
LSS_REG(0, arg1); LSS_REG(1, arg2); LSS_REG(2, arg3);
    LSS_REG(3, arg4); LSS_REG(4, arg5); LSS_REG(5, arg6);
    LSS_BODY(jlong, name, "r"(__r0), "r"(__r1), "r"(__r2), "r"(__r3),
                         "r"(__r4), "r"(__r5));
#else
#error "Unsupported architecture"
#endif
}
