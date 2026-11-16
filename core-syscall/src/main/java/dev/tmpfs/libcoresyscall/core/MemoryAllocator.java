package dev.tmpfs.libcoresyscall.core;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;

import dev.tmpfs.libcoresyscall.core.impl.ReflectHelper;

public class MemoryAllocator {

    private MemoryAllocator() {
        throw new AssertionError("no instances");
    }

    private static final long PAGE_SIZE = (int) MemoryAccess.getPageSize();

    // We use 16 bytes alignment for the allocated memory.
    private static final int ALLOCATE_UNIT_SIZE = 16;

    // The memory block is big, and we can't allocate it in one page.
    private static class DirectPageMemory implements IAllocatedMemory {

        private volatile long mAddress;
        private volatile long mSize;
        private volatile long mAllocatedSize;

        private DirectPageMemory(long address, long size, long allocatedSize) {
            mAddress = address;
            mSize = size;
            mAllocatedSize = allocatedSize;
        }

        @Override
        public long getAddress() {
            return mAddress;
        }

        @Override
        public long getSize() {
            return mSize;
        }

        @Override
        public synchronized void free() {
            if (mAddress != 0) {
                freeDirectPageMemory(mAddress, mAllocatedSize);
                mAddress = 0;
                mSize = 0;
                mAllocatedSize = 0;
            }
        }

        @Override
        public void close() {
            free();
        }

        @Override
        public boolean isFreed() {
            return mAddress == 0;
        }
    }

    // The memory block is not big, and we can allocate many blocks in one page.
    private static class ArenaBlockMemory implements IAllocatedMemory {

        private volatile long mAddress;
        private volatile long mSize;
        private volatile long mAllocatedSize;

        private ArenaBlockMemory(long address, long size, long allocatedSize) {
            mAddress = address;
            mSize = size;
            mAllocatedSize = allocatedSize;
        }

        @Override
        public long getAddress() {
            return mAddress;
        }

        @Override
        public long getSize() {
            return mSize;
        }

        @Override
        public synchronized void free() {
            if (mAddress != 0) {
                freeArenaMemory(mAddress, mAllocatedSize);
                mAddress = 0;
                mSize = 0;
                mAllocatedSize = 0;
            }
        }

        @Override
        public void close() {
            free();
        }

        @Override
        public boolean isFreed() {
            return mAddress == 0;
        }
    }

    private static DirectPageMemory allocateDirectPageMemory(long requestedSize) {
        // align the size to page size.
        long alignedSize = (requestedSize + PAGE_SIZE - 1) & -PAGE_SIZE;
        final int MAP_ANONYMOUS = 0x20;
        try {
            long address = Os.mmap(0, alignedSize, OsConstants.PROT_READ | OsConstants.PROT_WRITE,
                    OsConstants.MAP_PRIVATE | MAP_ANONYMOUS, null, 0);
            if (address == 0) {
                throw new AssertionError("mmap failed with size " + alignedSize + ", but no errno");
            }
            return new DirectPageMemory(address, requestedSize, alignedSize);
        } catch (ErrnoException e) {
            throw ReflectHelper.unsafeThrow(e);
        }
    }

    private static void freeDirectPageMemory(long address, long allocatedSize) {
        try {
            Os.munmap(address, allocatedSize);
        } catch (ErrnoException e) {
            throw ReflectHelper.unsafeThrow(e);
        }
    }

    private static class ArenaInfo {
        // The address of the page.
        public long startAddress;
        // Allocated block [address, alloc size].
        public final HashSet<long[]> allocatedAddress = new HashSet<>();

        // next free address in the page.
        public long nextFreeAddress;

        // end address of the page, exclusive, that is, startAddress + PAGE_SIZE.
        public long endAddress;

        public ArenaInfo(long pageAddress) {
            this.startAddress = pageAddress;
            this.nextFreeAddress = pageAddress;
            this.endAddress = pageAddress + PAGE_SIZE;
        }

    }

    private static final Object sArenaAllocLock = new Object();
    // A list of memory page, sorted by address.
    private static final ArrayList<ArenaInfo> sArenaPageList = new ArrayList<>();

    /**
     * Find a arena memory page that has enough free memory. If not found, return null.
     */
    private static ArenaBlockMemory tryAllocateArenaMemoryLocked(int allocateSize, int requestedSize) {
        if (allocateSize % 16 != 0) {
            throw new AssertionError("allocateSize must be 16 bytes aligned");
        }
        for (ArenaInfo info : sArenaPageList) {
            if (info.nextFreeAddress + allocateSize <= info.endAddress) {
                long address = info.nextFreeAddress;
                info.nextFreeAddress += allocateSize;
                info.allocatedAddress.add(new long[]{address, allocateSize});
                return new ArenaBlockMemory(address, requestedSize, allocateSize);
            }
        }
        return null;
    }

    private static void brkNewPageForArenaAllocationLocked() {
        // We need to allocate a new page for arena allocation.
        final int MAP_ANONYMOUS = 0x20;
        try {
            long address = Os.mmap(0, PAGE_SIZE, OsConstants.PROT_READ | OsConstants.PROT_WRITE,
                    OsConstants.MAP_PRIVATE | MAP_ANONYMOUS, null, 0);
            if (address == 0) {
                throw new AssertionError("mmap failed with size " + PAGE_SIZE + ", but no errno");
            }
            synchronized (sArenaAllocLock) {
                sArenaPageList.add(new ArenaInfo(address));
            }
        } catch (ErrnoException e) {
            throw ReflectHelper.unsafeThrow(e);
        }
    }

    private static ArenaBlockMemory allocateArenaMemory(int requestedSize) {
        if (requestedSize + ALLOCATE_UNIT_SIZE >= PAGE_SIZE) {
            // should not happen.
            throw new AssertionError("requested size is too big");
        }
        // align the size to unit size.
        // Even an empty allocation needs its own slot so it can be freed independently.
        final int allocateSizeBytes = Math.max(ALLOCATE_UNIT_SIZE,
                (requestedSize + ALLOCATE_UNIT_SIZE - 1) & -ALLOCATE_UNIT_SIZE);
        synchronized (sArenaAllocLock) {
            ArenaBlockMemory memory = tryAllocateArenaMemoryLocked(allocateSizeBytes, requestedSize);
            if (memory != null) {
                return memory;
            }
            brkNewPageForArenaAllocationLocked();
            memory = tryAllocateArenaMemoryLocked(allocateSizeBytes, requestedSize);
            if (memory != null) {
                return memory;
            }
            throw new AssertionError("failed to allocate arena memory, this should not happen");
        }
    }

    private static void freeArenaMemory(long address, long allocatedSize) {
        synchronized (sArenaAllocLock) {
            // find the page that contains the address.
            long pageAddress = address & -PAGE_SIZE;
            if (pageAddress == 0) {
                throw new AssertionError("invalid address to free");
            }
            // find the arena info.
            ArenaInfo arenaInfo = null;
            for (ArenaInfo info : sArenaPageList) {
                if (info.startAddress == pageAddress) {
                    arenaInfo = info;
                    break;
                }
            }
            if (arenaInfo == null) {
                throw new AssertionError("invalid address to free: address=" + address);
            }
            // remove the allocated address.
            boolean found = false;
            for (long[] allocated : arenaInfo.allocatedAddress) {
                if (allocated[0] == address) {
                    arenaInfo.allocatedAddress.remove(allocated);
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new AssertionError("invalid address to free: address=" + address);
            }
            // free the page if it's empty.
            if (arenaInfo.allocatedAddress.isEmpty()) {
                freeArenaPageLocked(pageAddress);
            }
        }
    }

    private static void freeArenaPageLocked(long arenaPageAddress) {
        for (ArenaInfo info : sArenaPageList) {
            if (info.startAddress == arenaPageAddress) {
                if (!info.allocatedAddress.isEmpty()) {
                    throw new AssertionError("page is not empty");
                }
                sArenaPageList.remove(info);
                try {
                    Os.munmap(arenaPageAddress, PAGE_SIZE);
                } catch (ErrnoException e) {
                    throw ReflectHelper.unsafeThrow(e);
                }
                return;
            }
        }
        throw new AssertionError("page not found");
    }

    /**
     * Allocate a memory block with the specified size. It's caller's responsibility to free the memory block.
     * A zero-sized allocation returns an independently releasable block with a logical size of zero.
     *
     * @param size   the size of the memory block
     * @param zeroed whether to zero the memory block
     * @return the allocated memory block
     * @throws IllegalArgumentException if size is negative or its page-aligned size cannot be represented
     */
    public static IAllocatedMemory allocate(long size, boolean zeroed) {
        if (size < 0) {
            throw new IllegalArgumentException("size is negative: " + size);
        }
        // The rounded size must fit both a Java long and the native size_t, before any state changes.
        long maxSize = NativeHelper.isCurrentRuntime64Bit() ? Long.MAX_VALUE : 0xffffffffL;
        if (size > (maxSize & -PAGE_SIZE)) {
            throw new IllegalArgumentException("size is too large to align: " + size);
        }
        // if requested size is 75% of page size, we allocate directly.
        if (size >= PAGE_SIZE * 3 / 4) {
            // anonymous memory maps are zeroed by default.
            return allocateDirectPageMemory(size);
        } else {
            IAllocatedMemory mem = allocateArenaMemory((int) size);
            // it may be used multiple times, so zero it here.
            if (zeroed) {
                MemoryAccess.memset(mem.getAddress(), 0, size);
            }
            return mem;
        }
    }

    /**
     * Allocate a memory block with the specified size. It's caller's responsibility to free the memory block.
     *
     * @param size the size of the memory block
     * @return the allocated memory block
     */
    public static IAllocatedMemory allocate(long size) {
        return allocate(size, false);
    }

    /**
     * Allocate a memory block and copy the specified bytes to it.
     *
     * @param bytes  the bytes to copy
     * @param offset the offset in the bytes
     * @param length the length of the bytes
     * @return the allocated memory block
     */
    public static IAllocatedMemory copyBytes(byte[] bytes, int offset, int length) {
        IAllocatedMemory memory = allocate(length);
        MemoryAccess.pokeByteArray(memory.getAddress(), bytes, offset, length);
        return memory;
    }

    /**
     * Allocate a memory block and copy the specified bytes to it.
     *
     * @param bytes the bytes to copy
     * @return the allocated memory block
     */
    public static IAllocatedMemory copyBytes(byte[] bytes) {
        return copyBytes(bytes, 0, bytes.length);
    }

    /**
     * Allocate a memory block and copy the specified string to it.
     *
     * @param string the string to copy
     * @return the allocated memory block
     */
    public static IAllocatedMemory copyString(String string) {
        return copyBytes(string.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Allocate a memory block and copy the specified C string to it.
     * The C string is a null-terminated string.
     *
     * @param string the C string to copy
     * @return the allocated memory block
     */
    public static IAllocatedMemory copyCString(String string) {
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        byte[] cString = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, cString, 0, bytes.length);
        cString[bytes.length] = 0;
        return copyBytes(cString);
    }

}
