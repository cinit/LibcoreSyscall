// make .text align to page boundary
__asm__(".text\n.p2align 12,0,4095\n___text_section: .globl ___text_section");

#include "jni_wrapper.cc"
#include "ashmem_utils.cc"
#include "hook_info.cc"
#include "fake_fstat64.cc"
#include "fake_mmap64.cc"
#include "syscall_wrapper.cc"
#include "libc_support.cc"
