#include "syscall_wrapper.h"

#include <sys/syscall.h>
#include <sys/mman.h>
#include <errno.h>
#include <fcntl.h>

#include "fake_fstat64.h"
#include "syscall_ext.h"

NO_MANGLE int lsw_mprotect(void* addr, size_t len, int prot) {
    const int NR_mprotect = __NR_mprotect;
    return (int) syscall_ext(NR_mprotect, (uintptr_t) addr, len, prot, 0, 0, 0);
}

NO_MANGLE ssize_t lsw_pread64(int fd, void* buf, size_t count, uint64_t offset) {
    const int NR_pread64 = __NR_pread64;
#if defined(__LP64__)
    return (ssize_t) syscall_ext(NR_pread64, fd, (uintptr_t) buf, count, offset, 0, 0);
#else
    uintptr_t off_h32 = offset >> 32u;
    uintptr_t off_l32 = offset & 0xffffffffu;
#if defined(__arm__)
    // arm eabi requires 64bit arguments to be passed in pairs
    return (ssize_t) syscall_ext(NR_pread64, fd, (uintptr_t) buf, count, 0, off_l32, off_h32);
#else
    return (ssize_t) syscall_ext(NR_pread64, fd, (uintptr_t) buf, count, off_l32, off_h32, 0);
#endif
#endif
}

NO_MANGLE void* lsw_mmap64(void* addr, size_t length, int prot, int flags, int fd, uint64_t offset) {
    uintptr_t res;
#if defined(__LP64__)
    res = syscall_ext(__NR_mmap, (uintptr_t) addr, length, prot, flags, fd, offset);
#else
    // check whether offset >> 12u exceeds the range of uintptr_t
    if ((offset >> 12u) > UINTPTR_MAX) {
        return reinterpret_cast<void*>(-EOVERFLOW);
    }
    res = syscall_ext(__NR_mmap2, (uintptr_t) addr, length, prot, flags, fd, offset >> 12u);
#endif
    return (void*) res;
}

NO_MANGLE int lsw_fstatfs64(int fd, kernel_statfs64_compat* buf) {
#if defined(__LP64__)
    const int NR_fstatfs = __NR_fstatfs;
#else
    const int NR_fstatfs = __NR_fstatfs64;
#endif
    return (int) syscall_ext(NR_fstatfs, fd, (uintptr_t) buf, 0, 0, 0, 0);
}

NO_MANGLE int lsw_munmap(void* addr, size_t len) {
    const int NR_munmap = __NR_munmap;
    return (int) syscall_ext(NR_munmap, (uintptr_t) addr, len, 0, 0, 0, 0);
}

NO_MANGLE ssize_t lsw_read(int fd, void* buf, size_t nbytes) {
    const int NR_read = __NR_read;
    return (ssize_t) syscall_ext(NR_read, fd, (uintptr_t) buf, nbytes, 0, 0, 0);
}

NO_MANGLE ssize_t lsw_write(int fd, const void* buf, size_t nbytes) {
    const int NR_write = __NR_write;
    return (ssize_t) syscall_ext(NR_write, fd, (uintptr_t) buf, nbytes, 0, 0, 0);
}

NO_MANGLE int lsw_openat(int dirfd, const char* pathname, int flags, mode_t mode) {
    const int NR_openat = __NR_openat;
    return (int) syscall_ext(NR_openat, dirfd, (uintptr_t) pathname, flags, mode, 0, 0);
}

NO_MANGLE int lsw_fstatat64(int dirfd, const char* pathname, kernel_stat64_compat* buf, int flags) {
#if defined(__LP64__)
    const int NR_fstatat = __NR_newfstatat;
#else
    const int NR_fstatat = __NR_fstatat64;
#endif
    return (int) syscall_ext(NR_fstatat, dirfd, (uintptr_t) pathname, (uintptr_t) buf, flags, 0, 0);
}

NO_MANGLE int lsw_stat64(const char* pathname, kernel_stat64_compat* buf) {
    return lsw_fstatat64(AT_FDCWD, pathname, buf, 0);
}

NO_MANGLE int lsw_fstat64(int fd, kernel_stat64_compat* buf) {
#if defined(__LP64__)
    const int NR_fstat = __NR_fstat;
#else
    const int NR_fstat = __NR_fstat64;
#endif
    return (int) syscall_ext(NR_fstat, fd, (uintptr_t) buf, 0, 0, 0, 0);
}

NO_MANGLE int lsw_close(int fd) {
    const int NR_close = __NR_close;
    return (int) syscall_ext(NR_close, fd, 0, 0, 0, 0, 0);
}

NO_MANGLE int lsw_ioctl(int fd, unsigned long request, void* arg) {
    const int NR_ioctl = __NR_ioctl;
    return (int) syscall_ext(NR_ioctl, fd, request, (uintptr_t) arg, 0, 0, 0);
}

NO_MANGLE NO_RETURN void lsw_exit_group(int status) {
    const int NR_exit_group = __NR_exit_group;
    syscall_ext(NR_exit_group, status, 0, 0, 0, 0, 0);
    __builtin_unreachable();
}
