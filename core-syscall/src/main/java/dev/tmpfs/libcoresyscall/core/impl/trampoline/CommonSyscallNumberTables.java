package dev.tmpfs.libcoresyscall.core.impl.trampoline;

import java.util.HashMap;

import dev.tmpfs.libcoresyscall.core.NativeHelper;
import dev.tmpfs.libcoresyscall.core.impl.arch.ShellcodeImpl_Arm32;
import dev.tmpfs.libcoresyscall.core.impl.arch.ShellcodeImpl_Arm64;
import dev.tmpfs.libcoresyscall.core.impl.arch.ShellcodeImpl_Riscv64;
import dev.tmpfs.libcoresyscall.core.impl.arch.ShellcodeImpl_X86;
import dev.tmpfs.libcoresyscall.core.impl.arch.ShellcodeImpl_X86_64;

public class CommonSyscallNumberTables {

    private CommonSyscallNumberTables() {
        throw new AssertionError("no instances");
    }

    private static final HashMap<Integer, ISyscallNumberTable> SYSCALL_NUMBER_TABLE_MAP = new HashMap<>(5);

    static {
        // add all supported ISAs
        SYSCALL_NUMBER_TABLE_MAP.put(NativeHelper.ISA_X86_64, ShellcodeImpl_X86_64.INSTANCE);
        SYSCALL_NUMBER_TABLE_MAP.put(NativeHelper.ISA_X86, ShellcodeImpl_X86.INSTANCE);
        SYSCALL_NUMBER_TABLE_MAP.put(NativeHelper.ISA_ARM64, ShellcodeImpl_Arm64.INSTANCE);
        SYSCALL_NUMBER_TABLE_MAP.put(NativeHelper.ISA_ARM, ShellcodeImpl_Arm32.INSTANCE);
        SYSCALL_NUMBER_TABLE_MAP.put(NativeHelper.ISA_RISCV64, ShellcodeImpl_Riscv64.INSTANCE);
    }

    public static ISyscallNumberTable get() {
        return get(NativeHelper.getCurrentRuntimeIsa());
    }

    public static ISyscallNumberTable get(int isa) {
        ISyscallNumberTable table = SYSCALL_NUMBER_TABLE_MAP.get(isa);
        if (table == null) {
            throw new UnsupportedOperationException("Unsupported ISA: " + NativeHelper.getIsaName(isa));
        }
        return table;
    }

}
