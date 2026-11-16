-keep,includedescriptorclasses class dev.tmpfs.libcoresyscall.core.** {
    native <methods>;
}

# CriticalNative changes the calling convention and must survive shrinking.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
