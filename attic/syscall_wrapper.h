#ifndef LIBCORE_SHELLCODE_SYSCALL_WRAPPERS_H
#define LIBCORE_SHELLCODE_SYSCALL_WRAPPERS_H

#include <sys/types.h>

#include "common_macros.h"
#include "kernel_abi.h"

// Linux syscall wrappers, are not the same as libc functions. They do not have an errno variable.
// They return the error code directly, and the caller should check the return value to see if it is an error.

#ifdef __LP64__

typedef struct kernel_statfs kernel_statfs64_compat;
typedef struct kernel_stat kernel_stat64_compat;

#else

typedef struct kernel_statfs64 kernel_statfs64_compat;
typedef struct kernel_stat64 kernel_stat64_compat;

#endif

NO_MANGLE ssize_t lsw_pread64(int fd, void* buf, size_t count, uint64_t offset);
NO_MANGLE int lsw_mprotect(void* addr, size_t len, int prot);
NO_MANGLE void* lsw_mmap64(void* addr, size_t len, int prot, int flags, int fd, uint64_t offset);
NO_MANGLE int lsw_fstatfs64(int fd, kernel_statfs64_compat* buf);
NO_MANGLE int lsw_munmap(void* addr, size_t len);
NO_MANGLE ssize_t lsw_read(int fd, void* buf, size_t nbytes);
NO_MANGLE ssize_t lsw_write(int fd, const void* buf, size_t nbytes);
NO_MANGLE int lsw_openat(int dirfd, const char* pathname, int flags, mode_t mode);
NO_MANGLE int lsw_stat64(const char* pathname, kernel_stat64_compat* buf);
NO_MANGLE int lsw_fstatat64(int dirfd, const char* pathname, kernel_stat64_compat* buf, int flags);
NO_MANGLE int lsw_fstat64(int fd, kernel_stat64_compat* buf);
NO_MANGLE int lsw_close(int fd);
NO_MANGLE int lsw_ioctl(int fd, unsigned long request, void* arg);
NO_MANGLE NO_RETURN void lsw_exit_group(int status);

#ifdef __cplusplus

// ioctl overloads
static inline int lsw_ioctl(int fd, unsigned long request, unsigned long arg) {
    return lsw_ioctl(fd, request, reinterpret_cast<void*>(arg));
}

static inline int lsw_ioctl(int fd, unsigned long request) {
    return lsw_ioctl(fd, request, nullptr);
}

#endif

#endif //LIBCORE_SHELLCODE_SYSCALL_WRAPPERS_H
