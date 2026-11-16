#include <jni.h>

#include <cstddef>

#include "common_macros.h"
#include "clear_cache.h"
#include "syscall_ext.h"

extern "C" JNIEXPORT void JNICALL
NativeBridge_breakpoint
        (JNIEnv* env, jclass _k) {
    __builtin_debugtrap();
}

extern "C" JNIEXPORT jlong JNICALL
NativeBridge_nativeSyscall
        (JNIEnv* env, jclass _k, jint number, jlong arg1, jlong arg2, jlong arg3, jlong arg4, jlong arg5, jlong arg6) {
    uintptr_t result = syscall_ext(number, (uintptr_t) arg1, (uintptr_t) arg2, (uintptr_t) arg3,
                                  (uintptr_t) arg4, (uintptr_t) arg5, (uintptr_t) arg6);
    // Sign-extend errors on 32-bit targets, but keep successful addresses unsigned.
    return is_error(result) ? -(jlong) errno_of(result) : (jlong) result;
}

extern "C" JNIEXPORT void JNICALL
NativeBridge_nativeClearCache
        (JNIEnv* env, jclass _, jlong address, jlong size) {
    void* start = (void*) address;
    auto sz = (size_t) size;
    void* end = (void*) ((uintptr_t) start + sz);
    __clear_cache(start, end);
}

extern "C" JNIEXPORT jlong JNICALL
NativeBridge_nativeCallPointerFunction0
        (JNIEnv* env, jclass _, jlong function) {
    typedef void* (* FunctionType)();
    auto f = (FunctionType) function;
    return (jlong) f();
}

extern "C" JNIEXPORT jlong JNICALL
NativeBridge_nativeCallPointerFunction1
        (JNIEnv* env, jclass _, jlong function, jlong arg1) {
    typedef void* (* FunctionType)(void*);
    auto f = (FunctionType) function;
    return (jlong) f((void*) arg1);
}

extern "C" JNIEXPORT jlong JNICALL
NativeBridge_nativeCallPointerFunction2
        (JNIEnv* env, jclass _, jlong function, jlong arg1, jlong arg2) {
    typedef void* (* FunctionType)(void*, void*);
    auto f = (FunctionType) function;
    return (jlong) f((void*) arg1, (void*) arg2);
}

extern "C" JNIEXPORT jlong JNICALL
NativeBridge_nativeCallPointerFunction3
        (JNIEnv* env, jclass _, jlong function, jlong arg1, jlong arg2, jlong arg3) {
    typedef void* (* FunctionType)(void*, void*, void*);
    auto f = (FunctionType) function;
    return (jlong) f((void*) arg1, (void*) arg2, (void*) arg3);
}

extern "C" JNIEXPORT jlong JNICALL
NativeBridge_nativeCallPointerFunction4
        (JNIEnv* env, jclass _, jlong function, jlong arg1, jlong arg2, jlong arg3, jlong arg4) {
    typedef void* (* FunctionType)(void*, void*, void*, void*);
    auto f = (FunctionType) function;
    return (jlong) f((void*) arg1, (void*) arg2, (void*) arg3, (void*) arg4);
}

extern "C" JNIEXPORT jlong JNICALL
NativeBridge_nativeGetJavaVM
        (JNIEnv* env, jclass _) {
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) == 0) {
        return (jlong) vm;
    } else {
        return 0;
    }
}
