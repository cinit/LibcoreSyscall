# Libcore-Syscall-ElfLoader

Libcore-Syscall-ElfLoader is a pure Java library for Android that allows you to make any Linux system calls and/or
load any in-memory ELF shared objects (lib*.so) without a writable path/mount point.

## Features

- Support Android 5.0 - 17.2
- Support any system calls (as long as they are permitted by the seccomp filter)
- Support loading in-memory ELF shared objects (lib*.so) without a file
- Support arm/arm64/x86/x86_64/riscv64 architectures
- Implemented in 100% pure Java 1.8
- No shared libraries (lib*.so) or assets files are shipped with the library (whole library as a single dex file version 035)
- No `System.loadLibrary` or `System.load` is used
- Support passive JNI registration (e.g. `Java_com_example_test_app_TestNativeLoader_nativeMethod`)
- No temporary files are created on the disk (does not require a writable path/mount point)
- No blocklisted hidden APIs are used
- Small, no dependencies (less than 100 KiB)

## Usage

The library provides the following classes:

- [MemoryAccess](core-syscall/src/main/java/dev/tmpfs/libcoresyscall/core/MemoryAccess.java) /
  [MemoryAllocator](core-syscall/src/main/java/dev/tmpfs/libcoresyscall/core/MemoryAllocator.java):
  Allocate and read/write native memory.
- [NativeAccess](core-syscall/src/main/java/dev/tmpfs/libcoresyscall/core/NativeAccess.java):
  Register JNI methods, or call native functions (such as `dlopen`, `dlsym`, etc.) directly.
- [Syscall](core-syscall/src/main/java/dev/tmpfs/libcoresyscall/core/Syscall.java): Make any Linux system calls.
- [DlExtLibraryLoader](core-syscall/src/main/java/dev/tmpfs/libcoresyscall/elfloader/DlExtLibraryLoader.java):
  Load any ELF shared objects (lib*.so) directly from memory.
- [NativeRegistrationHelper](core-syscall/src/main/java/dev/tmpfs/libcoresyscall/elfloader/NativeRegistrationHelper.java):
  Helper class to register native methods for the native library.

## Examples

Here are some examples of possible use cases.

### Load ELF Shared Object from Memory

Here is an example of how to load an ELF shared object directly from memory.
It loads the `libmmkv.so` shared object and calls the `MMKV.initialize` method.

See [TestNativeLoader.java](demo-app/src/main/java/com/example/test/app/TestNativeLoader.java) for the complete example.

```java
import com.tencent.mmkv.MMKV;

import dev.tmpfs.libcoresyscall.core.NativeAccess;
import dev.tmpfs.libcoresyscall.elfloader.DlExtLibraryLoader;

public static long initializeMMKV(@NonNull Context ctx) {
    String soname = "libmmkv.so";
    // get the ELF data from somewhere
    byte[] elfData = getElfData(soname);

    // load the ELF shared object from byte array
    // if it fails, it throws an UnsatisfiedLinkError
    long sHandle = DlExtLibraryLoader.dlopenExtFromMemory(elfData, soname, DlExtLibraryLoader.RTLD_NOW, 0, 0);

    // since dlopen from memory is not a standard function, ART does not know it
    // we need to call JNI_OnLoad manually, as if the shared object is loaded by System.loadLibrary
    long jniOnLoad = DlExtLibraryLoader.dlsym(sHandle, "JNI_OnLoad");
    if (jniOnLoad != 0) {
        long javaVm = NativeAccess.getJavaVM();
        long ret = NativeAccess.callPointerFunction(jniOnLoad, javaVm, 0);
        if (ret < 0) {
            throw new RuntimeException("JNI_OnLoad failed: " + ret);
        }
    } else {
        // should not happen, MMKV uses JNI_OnLoad to register native methods
        throw new IllegalStateException("JNI_OnLoad not found");
    }
    // initialize MMKV, since we have already loaded the libmmkv.so from memory
    // MMKV does not need to load the libmmkv.so shared object again
    MMKV.initialize(ctx, libName -> {
        // no-op
    });
    return sHandle;
}
```

For native libraries that use passive JNI registration (e.g. `Java_com_example_test_app_TestNativeLoader_nativeMethod`),
you can call the `NativeRegistrationHelper.registerNativeMethodsForLibrary` method to register the native methods.
See [TestNativeLoader.java](demo-app/src/main/java/com/example/test/app/TestNativeLoader.java) for the complete example.

`NativeRegistrationHelper.registerNativeMethodsForLibrary` enumerates all the exported symbols in the given ELF shared object,
and find the corresponding Java methods by the naming convention and register them.
However, it has higher overheads than the dynamic JNI registration (e.g. `env->RegisterNatives` in the `JNI_OnLoad` callback).

```java
import dev.tmpfs.libcoresyscall.core.NativeAccess;
import dev.tmpfs.libcoresyscall.elfloader.DlExtLibraryLoader;
import dev.tmpfs.libcoresyscall.elfloader.NativeRegistrationHelper;

public static long load(String soname) {
    byte[] elfData = getElfData(soname);
    long handle = DlExtLibraryLoader.dlopenExtFromMemory(elfData, soname, DlExtLibraryLoader.RTLD_NOW, 0, 0);
    if (handle != 0) {
        // register native methods for the library
        NativeRegistrationHelper.RegistrationSummary summary =
                NativeRegistrationHelper.registerNativeMethodsForLibrary(handle, elfData);
        // do some check with the native registration result
        Log.d("TestNativeLoader", soname + ": registerNativeMethodsForLibrary: " + summary);
        long jniOnLoad = DlExtLibraryLoader.dlsym(handle, "JNI_OnLoad");
        if (jniOnLoad != 0) {
            long javaVm = NativeAccess.getJavaVM();
            long ret = NativeAccess.callPointerFunction(jniOnLoad, javaVm, 0);
            if (ret < 0) {
                throw new IllegalStateException("JNI_OnLoad failed: " + ret);
            }
        }
    }
    return handle;
}
```

### Make System Calls

Here is an example of how to make syscalls with the library. It calls the `uname` system call to get the system information.

See [TestMainActivity.java](demo-app/src/main/java/com/example/test/app/TestMainActivity.java) for the complete example.

```java
import dev.tmpfs.libcoresyscall.core.IAllocatedMemory;
import dev.tmpfs.libcoresyscall.core.MemoryAccess;
import dev.tmpfs.libcoresyscall.core.MemoryAllocator;
import dev.tmpfs.libcoresyscall.core.NativeHelper;
import dev.tmpfs.libcoresyscall.core.Syscall;

public String unameDemo() {
    StringBuilder sb = new StringBuilder();
    int __NR_uname;
    switch (NativeHelper.getCurrentRuntimeIsa()) {
        case NativeHelper.ISA_X86_64:
            __NR_uname = 63;
            break;
        case NativeHelper.ISA_ARM64:
            __NR_uname = 160;
            break;
        // add other architectures here ...
    }
    // The struct of utsname can be found in <sys/utsname.h> in the NDK.
    // ...
    int releaseOffset = 65 * 2;
    // ...
    int utsSize = 65 * 6;
    try (IAllocatedMemory uts = MemoryAllocator.allocate(utsSize, true)) {
        long utsAddress = uts.getAddress();
        Syscall.syscall(__NR_uname, utsAddress);
        // ...
        sb.append("release = ").append(MemoryAccess.peekCString(utsAddress + releaseOffset));
        // ...
        sb.append("\n");
    } catch (ErrnoException e) {
        sb.append("ErrnoException: ").append(e.getMessage());
    }
    return sb.toString();
}
```

## The Tricks

- The Android-specific `libcore.io.Memory` and the evil `sun.misc.Unsafe` are used to access the native memory.
- Anonymous executable pages are allocated using the `android.system.Os.mmap` method.
- Native methods are registered with direct access to the `art::ArtMethod::entry_point_from_jni_` field.
- Cache synchronization is performed using `art::FlushCpuCaches` with `@CriticalNative` calling convention.
- Explanations of the shellcode details can be found in the [attic](attic/README.md) directory.

## Notice

- This library is not intended to be used in production code. You use it once and it may crash everywhere. It is only for a Proof of Concept.
- This library can only work on ART, not on OpenJDK HotSpot / OpenJ9 / GraalVM.
- The `execmem` SELinux permission is required to allocate anonymous executable memory. Fortunately, this permission is granted to all app domain processes.
- The `system_server` does not have the `execmem` permission. However, this is not true if you have a system-wide Xposed framework installed.

## FAQs

- Q: How can I use this library in an Xposed in my project?  
  A: You can either download the prebuilt AAB artifact from the [Releases](https://github.com/cinit/LibcoreSyscall/releases) page,
  build the library yourself and include the AAR artifact in your project, or add this repository as a submodule to your project.

- Q: I found that this repository does contain some C++ code. Didn't you say it's written in 100% pure Java 1.8?  
  A: The C++ code is only used for the shellcode generation. The shellcode is then embedded in the Java code as base64 strings.
  The C++ code does not need to be compiled when you build the library, and no NDK is required either.
  The library itself can be compiled into a single dex file (version 035) without any extra resource/so files
  and can be loaded into an InMemoryDexClassLoader directly.

- Q: Can I use this library to bypass the seccomp filter or SELinux policy?  
  A: No. The seccomp filter and SELinux policy are security mechanisms enforced by the kernel.
  This library does not bypass these security mechanisms. It only provides a way to access some native features that are not exposed by the Android SDK.

- Q: Can I use this library to run arbitrary native code in the system_server process?  
  A: Short answer: Yes only if you are in an Xposed module. Otherwise, no.  
  Long answer: The `system_server` process does not have the `execmem` permission, so it cannot allocate anonymous executable memory.
  But most Magisk-based system-wide Xposed frameworks typically patch the SELinux policy to grant the `execmem` permission to the `system_server` process,
  in which case you can use this library to run arbitrary native code in the `system_server` process.

- Q: Is this library well-tested?  
  A: No. This library is only a Proof of Concept. I have tested it on emulators API 21-35 and ABI arm/arm64/x86/x86_64/riscv64.
  However, I cannot guarantee that it will work on all real-world devices. It may crash everywhere. Use it at your own risk.
  I believe that this library SHOULD NOT be used in production code.

- Q: Can I use this library on mips/mips64 devices?\
  A: No. I have never seen a real-world mips/mips64 device running Android 5.0 (API 21) or later.

- If you have any other questions, please feel free to open an issue.

## Build

To build the library:

```shell
./gradlew :core-syscall:assembleDebug
```

To build the demo app:

```shell
./gradlew :demo-app:assembleDebug
```

## Credits

- [pine](https://github.com/canyie/pine): A dynamic Java method hook framework for ART.
- [linux-syscall-support](https://chromium.googlesource.com/linux-syscall-support): Linux syscall wrappers and kernel_* structures.
- [musl](https://musl.libc.org/): The musl C library.

## License

The library is licensed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0).
