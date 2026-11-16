package dev.tmpfs.libcoresyscall.core;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import dev.tmpfs.libcoresyscall.core.impl.ReflectHelper;

public class NativeHelper {

    private NativeHelper() {
    }

    private static final int ELF_CLASS_32 = 1;
    private static final int ELF_CLASS_64 = 2;
    public static final int ISA_NONE = 0;
    // EM_386 = 3
    public static final int ISA_X86 = (ELF_CLASS_32 << 16) | 3;
    // EM_X86_64 = 62
    public static final int ISA_X86_64 = (ELF_CLASS_64 << 16) | 62;
    // EM_ARM = 40
    public static final int ISA_ARM = (ELF_CLASS_32 << 16) | 40;
    // EM_AARCH64 = 183
    public static final int ISA_ARM64 = (ELF_CLASS_64 << 16) | 183;
    // EM_RISCV = 243
    public static final int ISA_RISCV64 = (ELF_CLASS_64 << 16) | 243;

    private static int sCurrentRuntimeIsa = ISA_NONE;

    public static int getCurrentRuntimeIsa() {
        if (sCurrentRuntimeIsa == ISA_NONE) {
            try (FileInputStream fis = new FileInputStream("/proc/self/exe")) {
                // we only need the first 32 bytes
                byte[] header = new byte[32];
                readExactly(fis, header, 0, header.length);
                int isa = getIsaFromElfHeader(header);
                // is an ISA we support?
                if (isa == ISA_X86 || isa == ISA_X86_64 || isa == ISA_ARM || isa == ISA_ARM64 || isa == ISA_RISCV64) {
                    sCurrentRuntimeIsa = isa;
                    return isa;
                } else {
                    throw new IllegalArgumentException("Unsupported ISA: " + isa);
                }
            } catch (IOException e) {
                throw ReflectHelper.unsafeThrow(e);
            }
        }
        return sCurrentRuntimeIsa;
    }

    /**
     * Get the ISA value from an ELF header.
     *
     * @param header ELF header, must be at least 32 bytes
     * @return ISA value
     * @throws IllegalArgumentException if the header is not a valid ELF header
     */
    public static int getIsaFromElfHeader(byte[] header) throws IllegalArgumentException {
        if (header.length < 32) {
            throw new IllegalArgumentException("Invalid ELF header: length < 32");
        }
        if (header[0] != (byte) 0x7f || header[1] != (byte) 'E' || header[2] != (byte) 'L' || header[3] != (byte) 'F') {
            throw new IllegalArgumentException("Invalid ELF heade: bad magic");
        }
        int elfClass = header[4];
        if (elfClass != ELF_CLASS_32 && elfClass != ELF_CLASS_64) {
            throw new IllegalArgumentException("Invalid ELF header: bad class: " + elfClass);
        }
        int offsetMachine = 16 + 2;
        byte m0 = header[offsetMachine];
        byte m1 = header[offsetMachine + 1];
        int machine = ((m1 << 8) & 0xff00) | (m0 & 0xff);
        return (elfClass << 16) | machine;
    }

    public static void readExactly(InputStream is, byte[] buf, int offset, int count) throws IOException {
        Objects.requireNonNull(is, "is == null");
        Objects.requireNonNull(buf, "buf == null");
        if (offset < 0 || count < 0 || offset + count > buf.length) {
            throw new IndexOutOfBoundsException("offset: " + offset + ", count: " + count + ", buf.length: " + buf.length);
        }
        int read = 0;
        while (read < count) {
            int len = is.read(buf, offset + read, count - read);
            if (len == -1) {
                throw new IOException("End of stream reached before reading " + count + " bytes");
            }
            read += len;
        }
    }

    @NonNull
    public static String getIsaName(int isa) {
        switch (isa) {
            case ISA_NONE:
                return "none";
            case ISA_X86:
                return "x86";
            case ISA_X86_64:
                return "x86_64";
            case ISA_ARM:
                return "arm";
            case ISA_ARM64:
                return "arm64";
            case ISA_RISCV64:
                return "riscv64";
            default:
                return "unknown(" + (isa >> 16) + ":" + (isa & 0xffff) + ")";
        }
    }

    /**
     * Create an ISA value from ELF class and machine.
     *
     * @param elfClass ELF class, either {@link #ELF_CLASS_32} or {@link #ELF_CLASS_64}
     * @param machine  machine value
     * @return ISA value
     */
    public static int forElfClassAndMachine(int elfClass, int machine) {
        if (elfClass != ELF_CLASS_32 && elfClass != ELF_CLASS_64) {
            throw new IllegalArgumentException("Invalid ELF class: " + elfClass);
        }
        if (machine <= 0 || machine > 0xffff) {
            throw new IllegalArgumentException("Invalid machine: " + machine);
        }
        return (elfClass << 16) | machine;
    }

    /**
     * Get the ISA value of an ELF file.
     *
     * @param file the ELF file
     * @return the ISA value
     * @throws IOException              if file not found or read error
     * @throws IllegalArgumentException if the file is not an ELF file
     */
    public static int getElfIsaFromFile(@NonNull File file) throws IOException, IllegalArgumentException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[32];
            readExactly(fis, header, 0, header.length);
            return getIsaFromElfHeader(header);
        }
    }

    public static boolean is64Bit(int isa) {
        return (isa >> 16) == ELF_CLASS_64;
    }

    public static boolean is32Bit(int isa) {
        return (isa >> 16) == ELF_CLASS_32;
    }

    public static boolean isCurrentRuntime64Bit() {
        return is64Bit(getCurrentRuntimeIsa());
    }

}
