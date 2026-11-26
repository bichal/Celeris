package net.bichal.mods.celeris.physics;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static net.bichal.mods.celeris.physics.BodyLayout.*;

/**
 * {@link BodyBatch} over one 64-byte-aligned slab from {@link Arena#ofShared()}
 * (shared: worker threads read and write it during {@link #step}). The slab,
 * the broadphase buckets and the pair buffer are allocated once here;
 * nothing below allocates.
 */
@SuppressWarnings({"Since15", "preview", "RedundantSuppression"})
final class SegmentBodyBatch extends AbstractBodyBatch<SegmentTerrain> {

    private static final ValueLayout.OfDouble F64 = ValueLayout.JAVA_DOUBLE;
    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT;

    private final Arena arena = Arena.ofShared();
    private final PhysicsKernel kernel;
    private final MemorySegment slab;
    private final MemorySegment buckets;
    private final MemorySegment pairs;

    SegmentBodyBatch(PhysicsKernel kernel, int requestedCapacity, PhysicsMode mode, int pairCapacity) {
        super(requestedCapacity, mode, pairCapacity);
        this.kernel = kernel;
        this.slab = arena.allocate(slabBytes(capacity), ALIGNMENT);
        this.buckets = arena.allocate((long) (bucketCount(capacity) + 1) * Integer.BYTES, ALIGNMENT);
        this.pairs = arena.allocate(Math.max(8L, 8L * this.pairCapacity), ALIGNMENT);
        initPadding();
    }

    private void checkCapacity(int slot) {
        if (slot < 0 || slot >= capacity) {
            throw new IndexOutOfBoundsException("slot " + slot + " of " + capacity);
        }
    }

    @Override
    public String engineName() {
        return kernel.name();
    }

    @Override
    double get(int column, int slot) {
        checkCapacity(slot);
        return slab.get(F64, f64Offset(column, capacity) + ((long) slot << 3));
    }

    @Override
    void put(int column, int slot, double value) {
        checkCapacity(slot);
        slab.set(F64, f64Offset(column, capacity) + ((long) slot << 3), value);
    }

    private long u32At(int column, int index) {
        checkCapacity(index);
        return u32Offset(column, capacity) + ((long) index << 2);
    }

    @Override
    int getFlags(int slot) {
        return slab.get(U32, u32At(FLAGS, slot));
    }

    @Override
    void putFlags(int slot, int value) {
        slab.set(U32, u32At(FLAGS, slot), value);
    }

    @Override
    void moveDeferred(int from, int to, int count) {
        long list = u32Offset(DEFERRED_LIST, capacity);
        for (int k = 0; k < count; k++) {
            slab.set(U32, list + ((long) (to + k) << 2), slab.get(U32, list + ((long) (from + k) << 2)));
        }
    }

    @Override
    SegmentTerrain stepTerrainOf(TerrainView terrain) {
        return (SegmentTerrain) terrain;
    }

    @Override
    int stepChunk(int begin, int end) {
        return kernel.step(slab, capacity, stepTerrain, begin, end, stepMode);
    }

    @Override
    public int deferred(int k) {
        checkDeferred(k);
        return slab.get(U32, u32At(DEFERRED_LIST, k));
    }

    @Override
    public int broadphase(double margin, boolean stopAtCapacity) {
        return kernel.broadphase(slab, capacity, size, buckets, margin, pairs, pairCapacity, stopAtCapacity);
    }

    @Override
    public int pairA(int k) {
        return pairs.getAtIndex(U32, 2L * k);
    }

    @Override
    public int pairB(int k) {
        return pairs.getAtIndex(U32, 2L * k + 1);
    }

    @Override
    public void close() {
        arena.close();
    }
}
