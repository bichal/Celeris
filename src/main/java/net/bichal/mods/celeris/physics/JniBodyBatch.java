package net.bichal.mods.celeris.physics;

import static net.bichal.mods.celeris.physics.BodyLayout.*;
import static net.bichal.mods.celeris.physics.RawMemory.UNSAFE;

/**
 * {@link BodyBatch} over one 64-byte-aligned off-heap slab, stepped by the
 * native kernel through JNI. Same layout and the same chunked parallel step as
 * {@code SegmentBodyBatch}; memory is reached with {@code Unsafe} instead of a
 * {@code MemorySegment}, so no FFM is needed. Slots are bounds-checked against
 * {@link #limit}, which {@link #close} drops to 0, so a stale or out-of-range
 * access throws instead of touching freed memory. Nothing below allocates
 * after construction.
 */
@SuppressWarnings("removal")
final class JniBodyBatch extends AbstractBodyBatch<JniTerrain> {

    /** {@code capacity} while open, 0 once closed: the bound every slot access checks. */
    private int limit;
    private final RawMemory slab;
    private final RawMemory buckets;
    private final RawMemory pairs;
    private final long base;
    private final int bucketInts;

    JniBodyBatch(int requestedCapacity, PhysicsMode mode, int pairCapacity) {
        super(requestedCapacity, mode, pairCapacity);
        this.limit = capacity;
        this.slab = RawMemory.allocate(slabBytes(capacity), ALIGNMENT);
        this.base = slab.address;
        this.bucketInts = bucketCount(capacity) + 1;
        this.buckets = RawMemory.allocate((long) bucketInts * Integer.BYTES, ALIGNMENT);
        this.pairs = RawMemory.allocate(Math.max(8L, 8L * this.pairCapacity), ALIGNMENT);
        initPadding();
    }

    private void checkLimit(int slot) {
        if (Integer.compareUnsigned(slot, limit) >= 0) {
            throw new IndexOutOfBoundsException("slot " + slot + " of " + capacity + (limit == 0 ? " (closed)" : ""));
        }
    }

    private long f64(int column, int slot) {
        checkLimit(slot);
        return base + f64Offset(column, capacity) + ((long) slot << 3);
    }

    private long u32(int column, int slot) {
        checkLimit(slot);
        return base + u32Offset(column, capacity) + ((long) slot << 2);
    }

    private void checkOpen() {
        if (limit == 0) {
            throw new IllegalStateException("batch closed");
        }
    }

    @Override
    public String engineName() {
        return JniPhysicsKernel.name();
    }

    @Override
    double get(int column, int slot) {
        return UNSAFE.getDouble(f64(column, slot));
    }

    @Override
    void put(int column, int slot, double value) {
        UNSAFE.putDouble(f64(column, slot), value);
    }

    @Override
    int getFlags(int slot) {
        return UNSAFE.getInt(u32(FLAGS, slot));
    }

    @Override
    void putFlags(int slot, int value) {
        UNSAFE.putInt(u32(FLAGS, slot), value);
    }

    @Override
    void moveDeferred(int from, int to, int count) {
        long list = base + u32Offset(DEFERRED_LIST, capacity);
        for (int k = 0; k < count; k++) {
            UNSAFE.putInt(list + ((long) (to + k) << 2), UNSAFE.getInt(list + ((long) (from + k) << 2)));
        }
    }

    @Override
    JniTerrain stepTerrainOf(TerrainView terrain) {
        checkOpen();
        JniTerrain t = (JniTerrain) terrain;
        t.checkOpen();
        return t;
    }

    @Override
    int stepChunk(int begin, int end) {
        return JniPhysicsKernel.step(base, capacity, stepTerrain.header(), begin, end, stepMode);
    }

    @Override
    public int deferred(int k) {
        checkDeferred(k);
        return UNSAFE.getInt(u32(DEFERRED_LIST, k));
    }

    @Override
    public int broadphase(double margin, boolean stopAtCapacity) {
        checkOpen();
        return JniPhysicsKernel.broadphase(base, capacity, size, buckets.address, bucketInts, margin,
                pairs.address, pairCapacity, stopAtCapacity);
    }

    @Override
    public int pairA(int k) {
        return UNSAFE.getInt(pairAt(k));
    }

    @Override
    public int pairB(int k) {
        return UNSAFE.getInt(pairAt(k) + Integer.BYTES);
    }

    private long pairAt(int k) {
        if (Integer.compareUnsigned(k, limit == 0 ? 0 : pairCapacity) >= 0) {
            throw new IndexOutOfBoundsException("pair " + k + " of " + pairCapacity);
        }
        return pairs.address + ((long) k << 3);
    }

    @Override
    public void close() {
        if (limit == 0) {
            return;
        }
        limit = 0;
        slab.free();
        buckets.free();
        pairs.free();
    }
}
