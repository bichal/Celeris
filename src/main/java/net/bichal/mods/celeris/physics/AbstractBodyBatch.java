package net.bichal.mods.celeris.physics;

import static net.bichal.mods.celeris.physics.BodyLayout.*;

/**
 * What the three {@link BodyBatch} engines (Java arrays, JNI over an off-heap slab, FFM over a
 * {@code MemorySegment}) share: the column layout of {@link BodyLayout}, slot bookkeeping, the
 * per-body accessors and the chunked parallel step on {@link ChunkWorkers}. A subclass only says
 * how a column cell is read and written, how one chunk is stepped and how deferred entries move.
 *
 * @param <T> the terrain type the engine steps against
 */
abstract class AbstractBodyBatch<T extends TerrainView> implements BodyBatch, ChunkWorkers.ChunkTask {

    final PhysicsMode mode;
    final int capacity;
    final int pairCapacity;
    private final int[] chunkDeferred;
    int size;
    int deferredCount;

    // Per-step state read by run(chunk) on worker threads; published by
    // ChunkWorkers' volatile generation write.
    T stepTerrain;
    int stepMode;
    /** Bodies per work unit of the current step ({@link BodyLayout#unit}). */
    int stepUnit = CHUNK;

    AbstractBodyBatch(int requestedCapacity, PhysicsMode mode, int pairCapacity) {
        this.mode = mode;
        this.capacity = roundCapacity(requestedCapacity);
        this.pairCapacity = Math.max(0, pairCapacity);
        this.chunkDeferred = new int[resultSlots(capacity)];
    }

    /** Column {@code column} of the f64 block at {@code slot}. */
    abstract double get(int column, int slot);

    abstract void put(int column, int slot, double value);

    abstract int getFlags(int slot);

    abstract void putFlags(int slot, int value);

    /** Moves {@code count} deferred-list entries from index {@code from} down to {@code to}. */
    abstract void moveDeferred(int from, int to, int count);

    /** Steps bodies {@code begin..end} against {@link #stepTerrain}; returns how many it deferred. */
    abstract int stepChunk(int begin, int end);

    /** The engine's own terrain, checked open; throws if the batch itself is closed. */
    abstract T stepTerrainOf(TerrainView terrain);

    /** Padding lanes must stay inert for the SIMD passes; call once storage exists. */
    final void initPadding() {
        for (int i = 0; i < capacity; i++) {
            put(FACTOR_H, i, 1.0);
            put(FACTOR_V, i, 1.0);
        }
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public PhysicsMode mode() {
        return mode;
    }

    @Override
    public int pairCapacity() {
        return pairCapacity;
    }

    @Override
    public int deferredCount() {
        return deferredCount;
    }

    @Override
    public int add(BodyParams params, float width, float height, double x, double y, double z,
                   double vx, double vy, double vz, boolean onGround, int phase) {
        if (size == capacity) {
            throw new IllegalStateException("batch full (" + capacity + ")");
        }
        int slot = size++;
        put(GRAVITY, slot, params.gravity());
        put(DRAG_AIR_H, slot, params.dragAirHorizontal());
        put(DRAG_V, slot, params.dragVertical());
        put(GROUND_SCALE, slot, params.groundScale());
        put(BELOW_OFFSET, slot, params.belowOffset());
        put(FACTOR_H, slot, 1.0);
        put(FACTOR_V, slot, 1.0);
        setDimensions(slot, width, height);
        setPosition(slot, x, y, z);
        setVelocity(slot, vx, vy, vz);
        putFlags(slot, params.throttleResting() ? BodyFlags.THROTTLE_RESTING : 0);
        setState(slot, onGround, false, phase);
        return slot;
    }

    @Override
    public int remove(int slot) {
        if (slot < 0 || slot >= size) {
            throw new IndexOutOfBoundsException("slot " + slot + " of " + size);
        }
        int last = --size;
        if (slot == last) {
            return -1;
        }
        for (int c = 0; c < F64_COLUMNS; c++) {
            put(c, slot, get(c, last));
        }
        putFlags(slot, getFlags(last));
        return last;
    }

    @Override
    public double x(int slot) {
        return get(POS_X, slot);
    }

    @Override
    public double y(int slot) {
        return get(POS_Y, slot);
    }

    @Override
    public double z(int slot) {
        return get(POS_Z, slot);
    }

    @Override
    public double vx(int slot) {
        return get(VEL_X, slot);
    }

    @Override
    public double vy(int slot) {
        return get(VEL_Y, slot);
    }

    @Override
    public double vz(int slot) {
        return get(VEL_Z, slot);
    }

    @Override
    public double halfWidth(int slot) {
        return get(HALF_WIDTH, slot);
    }

    @Override
    public double height(int slot) {
        return get(HEIGHT, slot);
    }

    @Override
    public int flags(int slot) {
        return getFlags(slot);
    }

    @Override
    public void setPosition(int slot, double x, double y, double z) {
        put(POS_X, slot, x);
        put(POS_Y, slot, y);
        put(POS_Z, slot, z);
    }

    @Override
    public void setVelocity(int slot, double vx, double vy, double vz) {
        put(VEL_X, slot, vx);
        put(VEL_Y, slot, vy);
        put(VEL_Z, slot, vz);
    }

    @Override
    public void setState(int slot, boolean onGround, boolean groundNoBlocks, int phase) {
        int f = getFlags(slot) & BodyFlags.THROTTLE_RESTING;
        f |= onGround ? BodyFlags.ON_GROUND : 0;
        f |= groundNoBlocks ? BodyFlags.GROUND_NO_BLOCKS : 0;
        putFlags(slot, BodyFlags.withPhase(f, phase));
    }

    @Override
    public void setDimensions(int slot, float width, float height) {
        // EntityDimensions.makeBoundingBox: float f = width / 2.0F; ... y + (double) height
        put(HALF_WIDTH, slot, width / 2.0F);
        put(HEIGHT, slot, height);
    }

    @Override
    public int step(TerrainView terrain, int rules) {
        stepTerrain = stepTerrainOf(terrain);
        stepMode = mode.nativeMode() | rules;
        ChunkWorkers workers = size >= PARALLEL_THRESHOLD ? CelerisPhysics.workers() : null;
        stepUnit = unit(size, workers == null ? 0 : workers.threadCount());
        int chunks = (size + stepUnit - 1) / stepUnit;
        // The pool is shared: if another batch is stepping on it (another thread),
        // this one runs its chunks itself, with the same results.
        if (workers == null || !workers.tryRun(this, chunks)) {
            for (int c = 0; c < chunks; c++) {
                run(c);
            }
        }
        // Compact the per-chunk deferred lists (each written from its chunk's
        // first slot) into one run at the start of the column.
        int total = 0;
        for (int c = 0; c < chunks; c++) {
            int d = chunkDeferred[c * RESULT_STRIDE];
            int from = c * stepUnit;
            if (from != total) {
                moveDeferred(from, total, d);
            }
            total += d;
        }
        deferredCount = total;
        stepTerrain = null;
        return total;
    }

    /** One chunk; called on the stepping thread or a worker. */
    @Override
    public final void run(int chunk) {
        int begin = chunk * stepUnit;
        int end = Math.min(begin + stepUnit, size);
        chunkDeferred[chunk * RESULT_STRIDE] = stepChunk(begin, end);
    }

    void checkDeferred(int k) {
        if (k < 0 || k >= deferredCount) {
            throw new IndexOutOfBoundsException("deferred " + k + " of " + deferredCount);
        }
    }
}
