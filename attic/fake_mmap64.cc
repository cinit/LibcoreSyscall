#include <cstdint>
#include <errno.h>

#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <linux/magic.h>

#include "common_macros.h"
#include "syscall_wrapper.h"
#include "syscall_ext.h"
#include "hook_info.h"
#include "fake_fstat64.h"
#include "clear_cache.h"

EXPORT void* fake_mmap64(void* const addr, const size_t length, const int prot,
                         const int flags, const int fd, const uint64_t offset) {
    const auto* info = (HookInfo*) get_hook_info();
    bool isInTmpfsForExec = false;
    bool isAshmem = false;
    bool useFallbackMapThenCopy = false;
    [[maybe_unused]] uint64_t fileSize = 0;
    if (fd >= 0 && (flags & MAP_PRIVATE) != 0 && (flags & MAP_ANONYMOUS) == 0) {
        // we need have an eye on MAP_PRIVATE on ashmem because it is not supported
        // check if the fd is an ashmem fd
        kernel_stat64_compat stat = {0};
        if (fake_fstat64(fd, &stat) == 0) {
            fileSize = stat.st_size;
            if (stat.st_dev == info->ashmem_dev_v && info->ashmem_dev_v != 0) {
                // since ashmem does not support MAP_PRIVATE, we need to manually handle it
                // if the later mmap succeeds, we need to copy the content from the ashmem with pread64
                isAshmem = true;
                useFallbackMapThenCopy = true;
            }
            if ((prot & PROT_EXEC) != 0) {
                // system_server do not have mmap executable permission on memfd
                // if system_server does have execmem, transform a memfd-exec-mapping into an anonymous-exec-mapping
                kernel_statfs64_compat fs = {0};
                if (lsw_fstatfs64(fd, &fs) == 0) {
                    if (fs.f_type == TMPFS_MAGIC) {
                        isInTmpfsForExec = true;
                    }
                }
            }
        }
    }
    // actually mmap
    int* the_errno = info->fn_dl_errno();
    if (offset % 4096u != 0) {
        *the_errno = EINVAL;
        return MAP_FAILED;
    }
    void* mapResult = lsw_mmap64(addr, length, prot | (useFallbackMapThenCopy ? PROT_WRITE : 0), flags, fd, offset);
    if (is_error(mapResult)) {
        bool shouldGiveUpTry = false;
        // it's not a permission issue, just return the original mmap result
        shouldGiveUpTry |= errno_of(mapResult) != EACCES;
        // Do not mess up with MAP_SHARED
        shouldGiveUpTry |= (flags & MAP_PRIVATE) == 0;
        // I can not imagine how this can fail except for execmem/OOM
        shouldGiveUpTry |= (flags & MAP_ANONYMOUS) != 0;
        // a map attempt without PROT_EXEC failed with EACCES
        // it just means the current process does not have read permission on the file
        // no need to try any further
        shouldGiveUpTry |= (prot & PROT_EXEC) == 0;
        // do not mess up with normal files, we only care about ashmem and tmpfs, aka. in-memory execution
        shouldGiveUpTry |= !isAshmem && !isInTmpfsForExec;
        if (shouldGiveUpTry) {
            // give up, just return the original mmap result
            *the_errno = errno_of(mapResult);
            return MAP_FAILED;
        }
        // try harder: for now, the case is that, a system_server is trying to map some fd with MAP_PRIVATE|PROT_EXEC
        bool isMapWithoutExecSuccess = [&]() {
            // try harder for the system_server, in likely case it has execmem permission (otherwise it cannot run this shellcode)
            if ((isAshmem || isInTmpfsForExec) && (flags & MAP_PRIVATE) != 0) {
                // do a MAP_PRIVATE without PROT_EXEC to check if the file is readable
                void* tmp = lsw_mmap64(addr, length, prot & ~PROT_EXEC, flags, fd, offset);
                bool success = !is_error(tmp);
                if (success) {
                    auto r = lsw_munmap(tmp, length);
                    assert_syscall_success(r);
                }
                return success;
            } else {
                return false;
            }
        }();
        if (isMapWithoutExecSuccess) {
            // it means the file is readable, but the current process has been blocked from mapping it with PROT_EXEC
            useFallbackMapThenCopy = true;
        } else {
            // let's just fail for now, maybe no permission to read the file
            *the_errno = EACCES;
            return MAP_FAILED;
        }
        if (is_error(mapResult)) {
            // do an anonymous mapping with PROT_WRITE and copy the content from the file
            mapResult = lsw_mmap64(addr, length, prot | PROT_WRITE, flags | MAP_ANONYMOUS, -1, 0);
            useFallbackMapThenCopy = true;
            // if this also fails, we just stop here
            if (is_error(mapResult)) {
                *the_errno = errno_of(mapResult);
                return MAP_FAILED;
            }
        }
    }
    if (useFallbackMapThenCopy) {
        uint64_t off = offset;
        uint64_t remaining = length;
        void* p = (void*) mapResult;
        // fill the content from ashmem to the private mapping
        while (remaining > 0) {
            size_t toRead = remaining > 4096u ? 4096u : (size_t) remaining;
            ssize_t r = lsw_pread64(fd, p, toRead, off);
            if (r <= 0) {
                if (r == -EINTR) {
                    // for EINTR, we just retry
                    continue;
                }
                // For r < 0, an error occurs,
                // we just break the loop and return the original mmap result,
                // as if we did not handle the MAP_PRIVATE on ashmem.
                // For r == 0 it means EOF, we just break.
                break;
            }
            remaining -= r;
            off += r;
            p = (void*) ((uintptr_t) p + r);
        }
        // restore the original protect
        lsw_mprotect((void*) mapResult, align_up(length, info->page_size), prot);
        if ((prot & PROT_EXEC) != 0) {
            uintptr_t pageStart = align_down((uintptr_t) mapResult, info->page_size);
            uintptr_t pageEnd = align_up((uintptr_t) mapResult + length, info->page_size);
            // flush the icache
            __clear_cache((void*) pageStart, (void*) pageEnd);
        }
    }
    return (void*) mapResult;
}

EXPORT void* fake_mmap(void* addr, size_t length, int prot, int flags, int fd, uintptr_t offset) {
    return fake_mmap64(addr, length, prot, flags, fd, offset);
}
