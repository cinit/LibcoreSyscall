#ifndef LIBCORE_SHELLCODE_FAKE_FSTAT64_H
#define LIBCORE_SHELLCODE_FAKE_FSTAT64_H

// for kernel_fstat64 and kernel_fstat structs
#include "kernel_abi.h"

#include "common_macros.h"

#ifdef __LP64__

typedef kernel_stat kernel_stat64_compat;

#else

typedef kernel_stat64 kernel_stat64_compat;

#endif

EXPORT int fake_fstat64(int fd, kernel_stat64_compat* buf);

#endif //LIBCORE_SHELLCODE_FAKE_FSTAT64_H
