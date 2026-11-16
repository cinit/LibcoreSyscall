package dev.tmpfs.libcoresyscall.elfloader;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

import dev.tmpfs.libcoresyscall.core.NativeAccess;

/**
 * A helper class for registering native methods in a library.
 */
public class NativeRegistrationHelper {

    private NativeRegistrationHelper() {
        throw new AssertionError("no instances");
    }

    /**
     * A summary of the registration results.
     */
    public static class RegistrationSummary {
        // The methods that were successfully registered by this invocation.
        public final ArrayList<Method> registeredMethods = new ArrayList<>();
        // The methods that were not found in the native library.
        public final ArrayList<Method> missedMethods = new ArrayList<>();
        // The methods that were already registered.
        public final ArrayList<Method> skippedMethods = new ArrayList<>();

        @NonNull
        @Override
        public String toString() {
            return "RegistrationSummary{" +
                    "registeredMethods=" + registeredMethods +
                    ", missedMethods=" + missedMethods +
                    ", skippedMethods=" + skippedMethods +
                    '}';
        }
    }

    public interface NativeLibrarySymbolResolver {
        /**
         * Resolves a symbol in the native library.
         *
         * @param symbol the symbol to resolve
         * @return the address of the symbol, or 0 if the symbol could not be found
         */
        long resolveSymbol(String symbol);
    }

    private static final int JNI_NATIVE_REGISTRATION_SUCCESS = 1;
    private static final int JNI_NATIVE_REGISTRATION_ALREADY_REGISTERED = 2;
    private static final int JNI_NATIVE_REGISTRATION_SYM_NOT_FOUND = 3;

    private static int findAndRegisterNativeMethodInternal(@NonNull NativeLibrarySymbolResolver resolver, @NonNull Method method) {
        // check if the method is already registered
        if (NativeAccess.getRegisteredNativeMethod(method) != 0) {
            return JNI_NATIVE_REGISTRATION_ALREADY_REGISTERED;
        }
        // ART: Try the short name then the long name...
        long address = resolver.resolveSymbol(getJniShortName(method));
        if (address == 0) {
            address = resolver.resolveSymbol(getJniLongName(method));
        }
        if (address == 0) {
            return JNI_NATIVE_REGISTRATION_SYM_NOT_FOUND;
        }
        NativeAccess.registerNativeMethod(method, address);
        return JNI_NATIVE_REGISTRATION_SUCCESS;
    }

    /**
     * Finds all native methods declared in the given classes and registers them with the specified library.
     * <p>
     * Note that native methods which are already registered will be skipped and will not be overridden.
     *
     * @param handle  the handle of the library, as returned by {@link DlExtLibraryLoader#dlopen}
     * @param klasses the classes to search for native methods
     * @return a summary of the registration process
     */
    public static RegistrationSummary findAndRegisterNativeMethods(long handle, @NonNull Class<?>[] klasses) {
        if (handle == 0) {
            throw new IllegalArgumentException("library handle is null");
        }
        return findAndRegisterNativeMethods(new DefaultNativeLibraryPublicSymbolResolver(handle), klasses);
    }

    /**
     * Finds all native methods declared in the given classes and registers them with the specified library.
     * <p>
     * Note that native methods which are already registered will be skipped and will not be overridden.
     *
     * @param resolver the symbol resolver to use to find the native method implementations
     * @param klasses  the classes to search for native methods
     * @return a summary of the registration process
     */
    public static RegistrationSummary findAndRegisterNativeMethods(@NonNull NativeLibrarySymbolResolver resolver, @NonNull Class<?>[] klasses) {
        RegistrationSummary summary = new RegistrationSummary();
        for (Class<?> klass : klasses) {
            // enumerate all declared methods in the class
            for (Method method : klass.getDeclaredMethods()) {
                if (Modifier.isNative(method.getModifiers())) {
                    int result = findAndRegisterNativeMethodInternal(resolver, method);
                    switch (result) {
                        case JNI_NATIVE_REGISTRATION_SUCCESS:
                            summary.registeredMethods.add(method);
                            break;
                        case JNI_NATIVE_REGISTRATION_ALREADY_REGISTERED:
                            summary.skippedMethods.add(method);
                            break;
                        case JNI_NATIVE_REGISTRATION_SYM_NOT_FOUND:
                            summary.missedMethods.add(method);
                            break;
                    }
                }
            }
        }
        return summary;
    }

    private static class DefaultNativeLibraryPublicSymbolResolver implements NativeLibrarySymbolResolver {

        private final long mHandle;

        public DefaultNativeLibraryPublicSymbolResolver(long handle) {
            mHandle = handle;
        }

        @Override
        public long resolveSymbol(String symbol) {
            if (TextUtils.isEmpty(symbol)) {
                return 0;
            }
            return DlExtLibraryLoader.dlsym(mHandle, symbol);
        }

    }

    /**
     * Gets the short JNI symbol name for the given method.
     *
     * @param method the method to get the JNI symbol name for
     * @return the short JNI symbol name, e.g. "Java_com_example_Foo_bar"
     */
    @NonNull
    public static String getJniShortName(@NonNull Method method) {
        return getJniShortName(method.getDeclaringClass().getName(), method.getName());
    }

    /**
     * Gets the long JNI symbol name for the given method.
     *
     * @param method the method to get the JNI symbol name for
     * @return the long JNI symbol name, e.g. "Java_com_example_Foo_bar__I"
     */
    @NonNull
    public static String getJniLongName(@NonNull Method method) {
        return getJniLongName(method.getDeclaringClass().getName(), method.getName(), method.getParameterTypes());
    }

    /**
     * Gets the short JNI symbol name for the given class and method names.
     *
     * @param className  the class name, e.g. "com.example.Foo"
     * @param methodName the method name, e.g. "bar"
     * @return the short JNI symbol name, e.g. "Java_com_example_Foo_bar"
     */
    @NonNull
    public static String getJniShortName(@NonNull String className, @NonNull String methodName) {
        return "Java_" + mangleForJni(className) + "_" + mangleForJni(methodName);
    }

    /**
     * Gets the long JNI symbol name for the given class, method, and argument types.
     *
     * @param className  the class name, e.g. "com.example.Foo"
     * @param methodName the method name, e.g. "bar"
     * @param argTypes   the argument types
     * @return the long JNI symbol name, e.g. "Java_com_example_Foo_bar__I"
     */
    @NonNull
    public static String getJniLongName(@NonNull String className, @NonNull String methodName, @NonNull Class<?>[] argTypes) {
        StringBuilder sb = new StringBuilder();
        sb.append("Java_");
        sb.append(mangleForJni(className));
        sb.append("_");
        sb.append(mangleForJni(methodName));
        sb.append("__");
        for (Class<?> argType : argTypes) {
            sb.append(mangleForJni(getTypeSignature(argType)));
        }
        return sb.toString();
    }

    /**
     * Get the JNI type signature for the given type.
     *
     * @param type the type, e.g. int.class or String.class
     * @return e.g. "I" for int.class, "Ljava/lang/String;" for String.class
     */
    @NonNull
    public static String getTypeSignature(@NonNull Class<?> type) {
        if (type == Void.TYPE) {
            return "V";
        } else if (type == Boolean.TYPE) {
            return "Z";
        } else if (type == Byte.TYPE) {
            return "B";
        } else if (type == Short.TYPE) {
            return "S";
        } else if (type == Character.TYPE) {
            return "C";
        } else if (type == Integer.TYPE) {
            return "I";
        } else if (type == Long.TYPE) {
            return "J";
        } else if (type == Float.TYPE) {
            return "F";
        } else if (type == Double.TYPE) {
            return "D";
        } else if (type.isArray()) {
            Class<?> c = type.getComponentType();
            assert c != null;
            return "[" + getTypeSignature(c);
        } else {
            return "L" + type.getName().replace('.', '/') + ";";
        }
    }

    // See http://java.sun.com/j2se/1.5.0/docs/guide/jni/spec/design.html#wp615 for the full rules.
    @NonNull
    private static String mangleForJni(@NonNull String s) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                result.append(ch);
            } else if (ch == '.' || ch == '/') {
                result.append("_");
            } else if (ch == '_') {
                result.append("_1");
            } else if (ch == ';') {
                result.append("_2");
            } else if (ch == '[') {
                result.append("_3");
            } else {
                result.append("_0").append(String.format("%04x", (int) ch));
            }
        }
        return result.toString();
    }

    /**
     * See {@link #registerNativeMethodsForLibrary(long, byte[], ClassLoader)}, where the current class loader is used.
     */
    public static RegistrationSummary registerNativeMethodsForLibrary(long handle, @NonNull byte[] elfData) {
        ClassLoader currentClassLoader = NativeRegistrationHelper.class.getClassLoader();
        assert currentClassLoader != null;
        return registerNativeMethodsForLibrary(handle, elfData, currentClassLoader);
    }

    /**
     * Gets the class name for the given JNI symbol name.
     *
     * @param symbolName the JNI symbol name, e.g. "Java_com_example_Foo_bar" or "Java_com_example_Foo_bar__I"
     * @return the class name, e.g. "com.example.Foo", or null if the symbol name is not a valid JNI symbol name
     */
    @Nullable
    public static String getClassNameForJniSymbolName(@NonNull String symbolName) {
        if (!symbolName.startsWith("Java_")) {
            return null;
        }
        char[] chars = symbolName.toCharArray();
        StringBuilder sb = new StringBuilder();
        int i = "Java_".length();
        try {
            while (i < chars.length) {
                char ch = chars[i];
                if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                    // normal character
                    sb.append(ch);
                    i++;
                } else if (ch == '_') {
                    // peek next char
                    char second = chars[i + 1];
                    if (second == '1') {
                        // '_1' -> '_'
                        sb.append('_');
                        i += 2;
                    } else if (second == '2') {
                        // '_2' -> ';'
                        sb.append(';');
                        i += 2;
                    } else if (second == '3') {
                        // '_3' -> '['
                        sb.append('[');
                        i += 2;
                    } else if (second == '0') {
                        // '_0xxxx' -> unicode character
                        int code = 0;
                        for (int j = i + 2; j < i + 6; j++) {
                            char digit = chars[j];
                            int value;
                            if (digit >= '0' && digit <= '9') {
                                value = digit - '0';
                            } else if (digit >= 'a' && digit <= 'f') {
                                value = digit - 'a' + 10;
                            } else if (digit >= 'A' && digit <= 'F') {
                                value = digit - 'A' + 10;
                            } else {
                                return null;
                            }
                            code = (code << 4) | value;
                        }
                        sb.append((char) code);
                        i += 6;
                    } else if ((second >= 'A' && second <= 'Z') || (second >= 'a' && second <= 'z') || (second >= '0' && second <= '9')) {
                        // normal '.' or '/'
                        sb.append('.');
                        i++;
                    } else if (second == '_') {
                        if (i + 2 == chars.length) {
                            // Ending with "__", which is a possible long name
                            break;
                        }
                        char third = chars[i + 2];
                        if (third == '0' || third == '1') {
                            // normal '.' or '/' followed by '_' or unicode character
                            sb.append('.');
                            i++;
                        } else if ((third >= 'A' && third <= 'Z') || (third >= 'a' && third <= 'z') || (third >= '0' && third <= '9') || third == '_') {
                            // "__" is for long name, the end of class name
                            break;
                        } else {
                            return null;
                        }
                    } else {
                        return null;
                    }
                } else {
                    return null;
                }
            }
            // find the end of the class name, that is, the last '.'
            int lastDot = sb.lastIndexOf(".");
            if (lastDot <= 0) {
                // should not happen
                return null;
            }
            return sb.substring(0, lastDot);
        } catch (IndexOutOfBoundsException e) {
            // bad jni name?
            return null;
        }
    }

    /**
     * Enumerates all JNI exported methods in the given library and registers them with the specified class loader.
     *
     * @param handle      the handle of the library, as returned by {@link DlExtLibraryLoader#dlopen}
     * @param elfData     the file content of the native library, should be the same as the one passed to {@link DlExtLibraryLoader#dlopen}
     * @param classLoader the class loader to register the methods with
     * @return a summary of the registration process
     */
    public static RegistrationSummary registerNativeMethodsForLibrary(
            long handle,
            @NonNull byte[] elfData,
            @NonNull ClassLoader classLoader
    ) {
        Objects.requireNonNull(elfData, "elfData");
        Objects.requireNonNull(classLoader, "classLoader");
        if (handle == 0) {
            throw new IllegalArgumentException("library handle is null");
        }
        // enumerate all exported symbols in the library starting with "Java_"
        ElfView elfView = new ElfView(ByteBuffer.wrap(elfData));
        HashSet<String> possibleClassNames = new HashSet<>();
        for (Map.Entry<String, Long> symbol : elfView.getDynamicSymbols().entrySet()) {
            if (symbol.getKey().startsWith("Java_")) {
                String className = getClassNameForJniSymbolName(symbol.getKey());
                if (className != null) {
                    possibleClassNames.add(className);
                } else {
                    // maybe bug in the library?
                    throw new IllegalStateException("invalid JNI symbol name: " + symbol.getKey());
                }
            }
        }
        HashSet<Class<?>> classes = new HashSet<>(possibleClassNames.size());
        for (String className : possibleClassNames) {
            try {
                classes.add(classLoader.loadClass(className));
            } catch (ClassNotFoundException e) {
                // ignore
            }
        }
        return findAndRegisterNativeMethods(handle, classes.toArray(new Class<?>[0]));
    }

}
