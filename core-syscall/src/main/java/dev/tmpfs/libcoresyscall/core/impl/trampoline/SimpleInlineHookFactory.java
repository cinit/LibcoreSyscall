package dev.tmpfs.libcoresyscall.core.impl.trampoline;

import java.util.HashMap;

import dev.tmpfs.libcoresyscall.core.NativeHelper;
import dev.tmpfs.libcoresyscall.core.impl.arch.ShellcodeImpl_Arm32;
import dev.tmpfs.libcoresyscall.core.impl.arch.ShellcodeImpl_Arm64;
import dev.tmpfs.libcoresyscall.core.impl.arch.ShellcodeImpl_Riscv64;
import dev.tmpfs.libcoresyscall.core.impl.arch.ShellcodeImpl_X86;
import dev.tmpfs.libcoresyscall.core.impl.arch.ShellcodeImpl_X86_64;

public class SimpleInlineHookFactory {

    private SimpleInlineHookFactory() {
        throw new AssertionError("no instances");
    }

    private static final HashMap<Integer, ISimpleInlineHook> CREATOR_MAP = new HashMap<>(5);

    static {
        // add all supported ISAs
        CREATOR_MAP.put(NativeHelper.ISA_X86_64, ShellcodeImpl_X86_64.INSTANCE);
        CREATOR_MAP.put(NativeHelper.ISA_X86, ShellcodeImpl_X86.INSTANCE);
        CREATOR_MAP.put(NativeHelper.ISA_ARM64, ShellcodeImpl_Arm64.INSTANCE);
        CREATOR_MAP.put(NativeHelper.ISA_ARM, ShellcodeImpl_Arm32.INSTANCE);
        CREATOR_MAP.put(NativeHelper.ISA_RISCV64, ShellcodeImpl_Riscv64.INSTANCE);
    }

    public static ISimpleInlineHook create() {
        return create(NativeHelper.getCurrentRuntimeIsa());
    }

    public static ISimpleInlineHook create(int isa) {
        ISimpleInlineHook creator = CREATOR_MAP.get(isa);
        if (creator == null) {
            throw new UnsupportedOperationException("Unsupported ISA: " + NativeHelper.getIsaName(isa));
        }
        return creator;
    }

}
