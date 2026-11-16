package dev.tmpfs.libcoresyscall.core;

import androidx.annotation.NonNull;

import java.lang.reflect.Member;

import dev.tmpfs.libcoresyscall.core.impl.ArtMethodHelper;
import dev.tmpfs.libcoresyscall.core.impl.NativeBridge;

public class NativeAccess {

    private NativeAccess() {
        throw new AssertionError("no instances");
    }

    static {
        NativeBridge.initializeOnce();
    }

    /**
     * Clear the cache for the specified memory range.
     * A zero-sized range is a no-op.
     *
     * @param address the start address of the memory range
     * @param size    the size of the memory range
     * @throws IllegalArgumentException if size is negative or the aligned range overflows
     */
    public static void clearCache(long address, long size) {
        if (size < 0) {
            throw new IllegalArgumentException("size is negative: " + size);
        }
        if (size == 0) {
            return;
        }
        long pageSize = NativeBridge.getPageSize();
        long alignedAddress = address & -pageSize;
        long offset = address - alignedAddress;
        if (size > Long.MAX_VALUE - offset - (pageSize - 1)) {
            throw new IllegalArgumentException("cache range is too large to align");
        }
        long alignedSize = (offset + size + pageSize - 1) & -pageSize;
        long end = alignedAddress + alignedSize;
        // Compare unsigned pointers without Long.compareUnsigned, which requires API 26.
        if ((end ^ Long.MIN_VALUE) < (alignedAddress ^ Long.MIN_VALUE)
                || (!NativeHelper.isCurrentRuntime64Bit()
                && ((address >>> 32) != 0 || (end >>> 32) != 0))) {
            throw new IllegalArgumentException("cache range overflows the address space");
        }
        NativeBridge.nativeClearCache(alignedAddress, alignedSize);
    }

    /**
     * Call the specified function pointer with the specified arguments.
     * The size of a single argument must fit in the word size of the platform, aka. uintptr_t.
     *
     * @param function the function pointer
     * @param args     the arguments, support up to 4 arguments
     * @return the result of the function
     */
    public static long callPointerFunction(long function, long... args) {
        if (function == 0) {
            throw new IllegalArgumentException("function is null");
        }
        switch (args.length) {
            case 0:
                return NativeBridge.nativeCallPointerFunction0(function);
            case 1:
                return NativeBridge.nativeCallPointerFunction1(function, args[0]);
            case 2:
                return NativeBridge.nativeCallPointerFunction2(function, args[0], args[1]);
            case 3:
                return NativeBridge.nativeCallPointerFunction3(function, args[0], args[1], args[2]);
            case 4:
                return NativeBridge.nativeCallPointerFunction4(function, args[0], args[1], args[2], args[3]);
            default:
                throw new AssertionError("Unsupported argument count: " + args.length);
        }
    }

    /**
     * Register a native method for the specified method/constructor.
     *
     * @param member         the method/constructor reflect object
     * @param nativeFunction the jni native function pointer
     */
    public static void registerNativeMethod(@NonNull Member member, long nativeFunction) {
        ArtMethodHelper.registerNativeMethod(member, nativeFunction);
    }

    /**
     * Unregister a native method for the specified method/constructor.
     *
     * @param method the method/constructor reflect object
     */
    public static void unregisterNativeMethod(@NonNull Member method) {
        ArtMethodHelper.unregisterNativeMethod(method);
    }

    /**
     * Get the registered native method for the specified method/constructor.
     *
     * @param method the method/constructor reflect object
     * @return the registered native function pointer, or 0 if not registered
     */
    public static long getRegisteredNativeMethod(@NonNull Member method) {
        return ArtMethodHelper.getRegisteredNativeMethod(method);
    }

    /**
     * Get the JavaVM pointer.
     * <p>
     * The returned JavaVM pointer can be used for calling JNI_OnLoad arguments.
     *
     * @return the JavaVM pointer
     */
    public static long getJavaVM() {
        return NativeBridge.nativeGetJavaVM();
    }

}
