/* Copyright 2005-2011 Google LLC
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of Google LLC nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * ---
 * Author: Markus Gutschke
 */

// Kernel structure subset of linux-syscall-support, revision
// 29164a80da4d41134950d76d55199ea33fbb9613. No libc syscall wrappers or errno/TLS.
#ifndef LIBCORE_SHELLCODE_KERNEL_ABI_H
#define LIBCORE_SHELLCODE_KERNEL_ABI_H

#include <cstddef>
#include <cstdint>

#if defined(__i386__) || defined(__ARM_EABI__)
typedef unsigned kernel_off_t;
struct kernel_stat64 {
  unsigned long long st_dev;
  unsigned char      __pad0[4];
  unsigned           __st_ino;
  unsigned           st_mode;
  unsigned           st_nlink;
  unsigned           st_uid;
  unsigned           st_gid;
  unsigned long long st_rdev;
  unsigned char      __pad3[4];
  long long          st_size;
  unsigned           st_blksize;
  unsigned long long st_blocks;
  unsigned           st_atime_;
  unsigned           st_atime_nsec_;
  unsigned           st_mtime_;
  unsigned           st_mtime_nsec_;
  unsigned           st_ctime_;
  unsigned           st_ctime_nsec_;
  unsigned long long st_ino;
};
struct kernel_statfs64 {
  unsigned long      f_type;
  unsigned long      f_bsize;
  unsigned long long f_blocks;
  unsigned long long f_bfree;
  unsigned long long f_bavail;
  unsigned long long f_files;
  unsigned long long f_ffree;
  struct { int val[2]; } f_fsid;
  unsigned long      f_namelen;
  unsigned long      f_frsize;
  unsigned long      f_spare[5];
};
#elif defined(__x86_64__)
typedef int64_t kernel_blkcnt_t;
typedef int64_t kernel_blksize_t;
typedef uint64_t kernel_dev_t;
typedef unsigned kernel_gid_t;
typedef uint64_t kernel_ino_t;
typedef unsigned kernel_mode_t;
typedef uint64_t kernel_nlink_t;
typedef int64_t kernel_off_t;
typedef uint64_t kernel_time_t;
typedef unsigned kernel_uid_t;
struct kernel_stat {
  kernel_dev_t       st_dev;
  kernel_ino_t       st_ino;
  kernel_nlink_t     st_nlink;
  kernel_mode_t      st_mode;
  kernel_uid_t       st_uid;
  kernel_gid_t       st_gid;
  unsigned           __pad0;
  kernel_dev_t       st_rdev;
  kernel_off_t       st_size;
  kernel_blksize_t   st_blksize;
  kernel_blkcnt_t    st_blocks;
  kernel_time_t      st_atime_;
  uint64_t           st_atime_nsec_;
  kernel_time_t      st_mtime_;
  uint64_t           st_mtime_nsec_;
  kernel_time_t      st_ctime_;
  uint64_t           st_ctime_nsec_;
  int64_t            __unused4[3];
};
#elif defined(__aarch64__) || (defined(__riscv) && __riscv_xlen == 64)
typedef long kernel_blkcnt_t;
typedef int kernel_blksize_t;
typedef unsigned long kernel_dev_t;
typedef unsigned int kernel_gid_t;
typedef unsigned long kernel_ino_t;
typedef unsigned int kernel_mode_t;
typedef unsigned int kernel_nlink_t;
typedef long kernel_off_t;
typedef long kernel_time_t;
typedef unsigned int kernel_uid_t;
struct kernel_stat {
  kernel_dev_t       st_dev;
  kernel_ino_t       st_ino;
  kernel_mode_t      st_mode;
  kernel_nlink_t     st_nlink;
  kernel_uid_t       st_uid;
  kernel_gid_t       st_gid;
  kernel_dev_t       st_rdev;
  unsigned long      __pad1;
  kernel_off_t       st_size;
  kernel_blksize_t   st_blksize;
  int                __pad2;
  kernel_blkcnt_t    st_blocks;
  kernel_time_t      st_atime_;
  unsigned long      st_atime_nsec_;
  kernel_time_t      st_mtime_;
  unsigned long      st_mtime_nsec_;
  kernel_time_t      st_ctime_;
  unsigned long      st_ctime_nsec_;
  unsigned int       __unused4;
  unsigned int       __unused5;
};
#else
#error "Unsupported architecture"
#endif

#if defined(__LP64__)
struct kernel_statfs {
  unsigned long      f_type;
  unsigned long      f_bsize;
  unsigned long      f_blocks;
  unsigned long      f_bfree;
  unsigned long      f_bavail;
  unsigned long      f_files;
  unsigned long      f_ffree;
  struct { int val[2]; } f_fsid;
  unsigned long      f_namelen;
  unsigned long      f_frsize;
  unsigned long      f_spare[5];
};
static_assert(sizeof(kernel_statfs) == 120, "kernel statfs layout");
static_assert(offsetof(kernel_stat, st_size) == 48, "kernel stat size offset");
#if defined(__x86_64__)
static_assert(sizeof(kernel_stat) == 144, "x86_64 kernel stat layout");
#else
static_assert(sizeof(kernel_stat) == 128, "kernel stat layout");
#endif
#else
#if defined(__arm__)
static_assert(sizeof(kernel_stat64) == 104, "ARM kernel stat64 layout");
static_assert(offsetof(kernel_stat64, st_size) == 48, "ARM kernel stat size offset");
static_assert(sizeof(kernel_statfs64) == 88, "ARM kernel statfs64 layout");
#else
static_assert(sizeof(kernel_stat64) == 96, "x86 kernel stat64 layout");
static_assert(offsetof(kernel_stat64, st_size) == 44, "x86 kernel stat size offset");
static_assert(sizeof(kernel_statfs64) == 84, "x86 kernel statfs64 layout");
#endif
#endif

#endif // LIBCORE_SHELLCODE_KERNEL_ABI_H
