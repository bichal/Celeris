package net.bichal.mods.celeris.physics;

import net.bichal.mods.celeris.util.UnsafeAccess;
import sun.misc.Unsafe;

/**
 * Aligned, zeroed off-heap blocks on {@code sun.misc.Unsafe} for the JNI
 * engine (no FFM, so no {@code MemorySegment}). {@code jdk.unsupported} is
 * resolved by default, so this needs no launch flag. Accessors are unchecked:
 * the callers ({@link JniBodyBatch}, {@link JniTerrain}) bounds-check slots
 * and sections before every access.
 */
@SuppressWarnings("removal")
final class RawMemory {

    static final Unsafe UNSAFE = UnsafeAccess.UNSAFE;
    static final long BYTE_ARRAY_BASE = Unsafe.ARRAY_BYTE_BASE_OFFSET;

    /** The block as allocated ({@link #raw}) and the aligned address inside it ({@link #address}). */
    private final long raw;
    final long address;
    final long size;

    private RawMemory(long raw, long address, long size) {
        this.raw = raw;
        this.address = address;
        this.size = size;
    }

    /** Allocates {@code size} zeroed bytes at a multiple of {@code alignment} (a power of two). */
    static RawMemory allocate(long size, long alignment) {
        UnsafeAccess.checkAllocation(size, alignment);
        long raw = UNSAFE.allocateMemory(Math.max(1, size + alignment - 1)); // throws OutOfMemoryError
        long address = (raw + alignment - 1) & -alignment;
        UNSAFE.setMemory(address, size, (byte) 0);
        return new RawMemory(raw, address, size);
    }

    void free() {
        UNSAFE.freeMemory(raw);
    }

    /** Round-trips one value, so a JVM that forbids Unsafe memory access fails here and not mid-tick. */
    static void probe() {
        RawMemory m = allocate(Long.BYTES, Long.BYTES);
        try {
            UNSAFE.putLong(m.address, 0x5EEDL);
            if (UNSAFE.getLong(m.address) != 0x5EEDL) {
                throw new IllegalStateException("Unsafe round-trip mismatch");
            }
        } finally {
            m.free();
        }
    }
}
