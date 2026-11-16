#include "fake_fstat64.h"

#include <sys/syscall.h>

#include "syscall_ext.h"
#include "hook_info.h"
#include "ashmem_utils.h"

EXPORT int fake_fstat64(int fd, kernel_stat64_compat* buf) {
    auto* info = (HookInfo*) get_hook_info();
#if defined(__LP64__)
    struct kernel_stat* stat = buf;
    const int NR_fstat = __NR_fstat;
#else
    struct kernel_stat64* stat = buf;
    const int NR_fstat = __NR_fstat64;
#endif
    uintptr_t res = syscall_ext(NR_fstat, fd, (uintptr_t) stat, 0, 0, 0, 0);
    if (is_error(res)) {
        // set errno and return -1
        int* the_errno = info->fn_dl_errno();
        *the_errno = -(int) (ssize_t) res;
        return -1;
    }
    // check if the fd is an ashmem fd
    if (stat->st_dev == info->ashmem_dev_v && info->ashmem_dev_v != 0 && stat->st_size == 0) {
        // fake the stat.st_size with ashmem size
        ssize_t ssize = ashmem_dev_get_size_region(fd);
        if (!is_error(ssize)) {
            // overwrite the st_size
            stat->st_size = (kernel_off_t) (size_t) ssize;
            // override the st_ino with 0
            // to avoid the linker being confused with the same inode number for different shared libraries
            stat->st_ino = 0;
        }
    }
    return 0;
}
