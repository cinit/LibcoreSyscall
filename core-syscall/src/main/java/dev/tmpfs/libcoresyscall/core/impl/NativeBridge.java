package dev.tmpfs.libcoresyscall.core.impl;

import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.Map;

import dev.tmpfs.libcoresyscall.core.Syscall;
import dev.tmpfs.libcoresyscall.core.impl.trampoline.BaseShellcode;
import dev.tmpfs.libcoresyscall.core.impl.trampoline.CommonSyscallNumberTables;
import dev.tmpfs.libcoresyscall.core.impl.trampoline.ISyscallNumberTable;
import dev.tmpfs.libcoresyscall.core.impl.trampoline.TrampolineCreatorFactory;
import dev.tmpfs.libcoresyscall.core.impl.trampoline.TrampolineInfo;
import libcore.io.Memory;

public class NativeBridge {

    private NativeBridge() {
        throw new AssertionError("no instances");
    }

    private static long sPageSize = 0;
    private static long sTrampolineBase = 0;
    private static boolean sNativeMethodRegistered = false;
    private static boolean sTrampolineSetReadOnly = false;
    private static BaseShellcode sShellcode = null;

    public static native long nativeSyscall(int number, long arg1, long arg2, long arg3, long arg4, long arg5, long arg6);

    public static native void nativeClearCache(long address, long size);

    public static native long nativeCallPointerFunction0(long function);

    public static native long nativeCallPointerFunction1(long function, long arg1);

    public static native long nativeCallPointerFunction2(long function, long arg1, long arg2);

    public static native long nativeCallPointerFunction3(long function, long arg1, long arg2, long arg3);

    public static native long nativeCallPointerFunction4(long function, long arg1, long arg2, long arg3, long arg4);

    public static native long nativeGetJavaVM();

    public static long getPageSize() {
        long ps = sPageSize;
        if (ps == 0) {
            ps = Os.sysconf(OsConstants._SC_PAGESIZE);
            if (ps != 4096 && ps != 16384 && ps != 65536) {
                throw new AssertionError("Unexpected page size: " + ps);
            }
            sPageSize = ps;
        }
        return ps;
    }

    /**
     * Get the base address of the trampoline, this address is typically NOT useful.
     * If the trampoline is not initialized, this method will return 0.
     */
    public static long getTrampolineBase() {
        return sTrampolineBase;
    }

    @NonNull
    public static BaseShellcode getShellcode() {
        if (sShellcode == null) {
            sShellcode = TrampolineCreatorFactory.create();
        }
        return sShellcode;
    }

    public static synchronized void initializeOnce() {
        if (!sNativeMethodRegistered) {
            // 1. Get the page size.
            long pageSize = getPageSize();
            // 2. Prepare the trampoline.
            BaseShellcode shellcode = getShellcode();
            TrampolineInfo trampoline = shellcode.generateTrampoline();
            // 3. Allocate a memory region for the trampoline.
            final int MAP_ANONYMOUS = 0x20;
            long address;
            try {
                address = Os.mmap(0, pageSize,
                        OsConstants.PROT_READ | OsConstants.PROT_WRITE | OsConstants.PROT_EXEC,
                        OsConstants.MAP_PRIVATE | MAP_ANONYMOUS, null, 0);
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.EACCES) {
                    throw new UnsupportedOperationException("mmap failed with EACCES. Please check SELinux policy for execmem permission.");
                }
                // I have no idea what happened.
                throw ReflectHelper.unsafeThrow(e);
            }
            try {
                // 4. Write the trampoline and synchronize caches before executing any of it.
                Memory.pokeByteArray(address, trampoline.trampolineCode, 0, trampoline.trampolineCode.length);
                if (Build.VERSION.SDK_INT >= 26) {
                    NativeCacheHelper.clearCache(address, address + trampoline.trampolineCode.length);
                } else {
                    ProcMemCacheHelper.clearCache(address, address + trampoline.trampolineCode.length);
                }
            } catch (Throwable e) {
                // No native entry points refer to this allocation yet, so it is safe to release.
                try {
                    Os.munmap(address, pageSize);
                } catch (ErrnoException cleanupError) {
                    e.addSuppressed(cleanupError);
                }
                throw ReflectHelper.unsafeThrow(e);
            }
            // 5. Register the native methods.
            for (Map.Entry<Method, Integer> methods : trampoline.nativeEntryOffsetMap.entrySet()) {
                Method method = methods.getKey();
                int offset = methods.getValue();
                long function = address + (long) offset;
                ArtMethodHelper.registerNativeMethod(method, function);
            }
            sTrampolineBase = address;
            sNativeMethodRegistered = true;
        }
        if (!sTrampolineSetReadOnly) {
            // 6. Set the memory region to read-only.
            long pageSize = getPageSize();
            ISyscallNumberTable sysnr = CommonSyscallNumberTables.get();
            try {
                long rc = NativeBridge.nativeSyscall(sysnr.__NR_mprotect(), sTrampolineBase, pageSize,
                        OsConstants.PROT_READ | OsConstants.PROT_EXEC, 0, 0, 0);
                Syscall.checkResultOrThrow(rc, "mprotect");
            } catch (ErrnoException e) {
                throw ReflectHelper.unsafeThrow(e);
            }
            sTrampolineSetReadOnly = true;
        }
        // Done.
    }
}
