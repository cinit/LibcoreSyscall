package dev.tmpfs.libcoresyscall.core.impl;

import android.annotation.SuppressLint;
import android.os.Build;

import androidx.annotation.RequiresApi;

import java.io.IOException;

import dalvik.annotation.optimization.CriticalNative;
import dev.tmpfs.libcoresyscall.core.NativeHelper;
import dev.tmpfs.libcoresyscall.elfloader.SymbolResolver;

/**
 * Cache synchronization using art::FlushCpuCaches with @CriticalNative calling convention.
 * This is used in native trampoline setup before NativeBridge is available.
 **/
@RequiresApi(26)
final class NativeCacheHelper {

    private NativeCacheHelper() {
        throw new AssertionError("no instances");
    }

    // Called under NativeBridge's initialization lock, before publishing any trampoline entry.
    static void clearCache(long begin, long end) {
        Throwable unavailable = null;
        try {
            if (tryClearCache(begin, end)) {
                return;
            }
        } catch (IOException | ReflectiveOperationException e) {
            // An unreadable system ELF or unavailable binding also needs the kernel fallback.
            unavailable = e;
        }
        try {
            ProcMemCacheHelper.clearCache(begin, end);
        } catch (Throwable e) {
            if (unavailable != null) {
                e.addSuppressed(unavailable);
            }
            throw ReflectHelper.unsafeThrow(e);
        }
    }

    private static boolean tryClearCache(long begin, long end)
            throws IOException, ReflectiveOperationException {
        if (Build.VERSION.SDK_INT >= 29) {
            long function = findSymbol("_ZN3art14FlushCpuCachesEPvS0_", "libartbase.so");
            if (function != 0) {
                boolean success;
                if (NativeHelper.isCurrentRuntime64Bit()) {
                    bind("flushCpuCaches64", function, long.class, long.class);
                    success = SystemCalls.flushCpuCaches64(begin, end);
                } else {
                    bind("flushCpuCaches32", function, int.class, int.class);
                    success = SystemCalls.flushCpuCaches32((int) begin, (int) end);
                }
                if (!success) {
                    throw new IllegalStateException("art::FlushCpuCaches failed");
                }
                return true;
            }
        }

        int isa = NativeHelper.getCurrentRuntimeIsa();
        if (isa == NativeHelper.ISA_X86 || isa == NativeHelper.ISA_X86_64) {
            // Coherent instruction/data caches; older x86 ART has no cache helper symbol.
            return true;
        }
        if (isa == NativeHelper.ISA_ARM64) {
            // API 26-28 ART uses an inline wrapper around this compiler runtime helper.
            long function = findSymbol("__clear_cache", "libart.so", "libartbase.so");
            if (function != 0) {
                bind("clearCache64", function, long.class, long.class);
                SystemCalls.clearCache64(begin, end);
                return true;
            }
        } else if (isa == NativeHelper.ISA_ARM) {
            long function = findSymbol("cacheflush", "libc.so");
            if (function != 0) {
                bind("cacheflush32", function, int.class, int.class, int.class);
                if (SystemCalls.cacheflush32((int) begin, (int) end, 0) != 0) {
                    throw new IllegalStateException("libc cacheflush failed");
                }
                return true;
            }
        }
        return false;
    }

    private static long findSymbol(String symbol, String... modules) throws IOException {
        for (String module : modules) {
            try {
                long address = SymbolResolver.getModule(module).getSymbolAddress(symbol);
                if (address != 0) {
                    return address;
                }
            } catch (SymbolResolver.NoSuchModuleException ignored) {
                // The ART library split differs between Android releases.
            }
        }
        return 0;
    }

    private static void bind(String name, long function, Class<?>... parameterTypes)
            throws ReflectiveOperationException {
        // Finish initialization before replacing the entry. This holder has no managed state,
        // and binding and first invocation happen on the same thread under the bootstrap lock.
        Class<?> holder = SystemCalls.class;
        Class.forName(holder.getName(), true, holder.getClassLoader());
        // Do not use NativeAccess here: its initializer would reenter NativeBridge.
        ArtMethodHelper.registerNativeMethod(holder.getDeclaredMethod(name, parameterTypes), function);
    }

    // CriticalNative is supported since API 26, although it became a public API in API 34.
    @SuppressLint("NewApi")
    private static final class SystemCalls {

        @CriticalNative
        private static native boolean flushCpuCaches64(long begin, long end);

        @CriticalNative
        private static native boolean flushCpuCaches32(int begin, int end);

        @CriticalNative
        private static native void clearCache64(long begin, long end);

        @CriticalNative
        private static native int cacheflush32(int begin, int end, int flags);
    }
}
