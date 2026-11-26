package net.bichal.mods.celeris.util;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * The one {@code sun.misc.Unsafe} lookup behind the Unsafe memory backend and the JNI physics
 * engine. {@code jdk.unsupported} is resolved by default, so this needs no launch flag. Not API.
 */
@SuppressWarnings("removal")
public final class UnsafeAccess {

    public static final Unsafe UNSAFE = load();

    private UnsafeAccess() {
    }

    private static Unsafe load() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Throws unless {@code size} is non-negative and {@code alignment} a power of two. */
    public static void checkAllocation(long size, long alignment) {
        if (size < 0 || alignment <= 0 || Long.bitCount(alignment) != 1) {
            throw new IllegalArgumentException("Invalid allocation: size=" + size + ", alignment=" + alignment);
        }
    }
}
