package dev.tmpfs.libcoresyscall.core.impl;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.IOException;

/** Cache synchronization through the kernel, without executing the new trampoline. */
final class ProcMemCacheHelper {

    private ProcMemCacheHelper() {
        throw new AssertionError("no instances");
    }

    /**
     * Rewrite every page covering [begin, end) through /proc/self/mem. The caller must own
     * the complete readable, writable, executable pages, with no concurrent writers/executors.
     * Kernel copy_to_user_page synchronizes executable mappings over the bytes written;
     * rewriting only one byte does not synchronize the rest of the page.
     * Note that this only works for RWX pages.
     */
    static void clearCache(long begin, long end) {
        if (begin == end) {
            return;
        }
        if ((end ^ Long.MIN_VALUE) < (begin ^ Long.MIN_VALUE)) {
            throw new IllegalArgumentException("invalid cache range");
        }
        int pageSize = (int) NativeBridge.getPageSize();
        long firstPage = begin & -(long) pageSize;
        long lastPage = (end - 1) & -(long) pageSize;
        byte[] page = new byte[pageSize];
        try (MemoryFile mem = new MemoryFile()) {
            for (long address = firstPage; ; address += pageSize) {
                seek(mem.fd, address);
                transferPage(mem.fd, page, false);
                // read advances the file offset. Seek back before writing identical bytes.
                seek(mem.fd, address);
                transferPage(mem.fd, page, true);
                if (address == lastPage) {
                    break;
                }
            }
        } catch (ErrnoException | IOException e) {
            throw ReflectHelper.unsafeThrow(e);
        }
    }

    private static void seek(FileDescriptor fd, long address) throws ErrnoException, IOException {
        while (true) {
            try {
                // libcore calls lseek64 even in a 32-bit process, including on API 21.
                if (Os.lseek(fd, address, OsConstants.SEEK_SET) != address) {
                    throw new IOException("Unexpected /proc/self/mem seek result");
                }
                return;
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.EINTR) {
                    throw e;
                }
            }
        }
    }

    private static void transferPage(FileDescriptor fd, byte[] page, boolean write)
            throws ErrnoException, IOException {
        int offset = 0;
        while (offset < page.length) {
            int count;
            try {
                count = write ? Os.write(fd, page, offset, page.length - offset)
                        : Os.read(fd, page, offset, page.length - offset);
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.EINTR) {
                    continue;
                }
                throw e;
            }
            if (count <= 0) {
                throw new IOException("Incomplete /proc/self/mem " + (write ? "write" : "read"));
            }
            offset += count;
        }
    }

    private static final class MemoryFile implements AutoCloseable {
        final FileDescriptor fd;

        MemoryFile() throws ErrnoException {
            // Linux O_CLOEXEC exists on API 21, but OsConstants exposes it only from API 27.
            final int O_CLOEXEC = 0x80000;
            fd = Os.open("/proc/self/mem", OsConstants.O_RDWR | O_CLOEXEC, 0);
        }

        @Override
        public void close() throws ErrnoException {
            // Do not retry close: the descriptor may already have been released on EINTR.
            Os.close(fd);
        }
    }
}
