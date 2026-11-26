package net.bichal.mods.celeris.physics;

import static net.bichal.mods.celeris.physics.BodyLayout.*;

/**
 * {@link BodyBatch} on plain Java arrays: the engine used when the JVM has no
 * FFM (Minecraft 1.21.x without {@code --enable-preview}) or the native
 * library is missing. Same Structure-of-Arrays layout as the off-heap slab
 * -- one array per column, all allocated here, nothing allocated per tick --
 * and the same chunked parallel step on {@link ChunkWorkers}, run by
 * {@link JavaPhysicsKernel}, the reference implementation the native kernel
 * is tested against.
 */
final class HeapBodyBatch extends AbstractBodyBatch<HeapTerrain> {

    /** One array per f64 column, indexed by {@link BodyLayout}'s column ids; the names below alias them. */
    private final double[][] columns = new double[F64_COLUMNS][];
    final double[] px, py, pz, vx, vy, vz, halfWidth, height, gravity, dragAirH, dragV, groundScale, belowOffset,
            factorH, factorV;
    final int[] flags, deferredList, cellHash, sorted, buckets;
    private final int[] pairs;

    HeapBodyBatch(int requestedCapacity, PhysicsMode mode, int pairCapacity) {
        super(requestedCapacity, mode, pairCapacity);
        int c = capacity;
        for (int i = 0; i < F64_COLUMNS; i++) {
            columns[i] = new double[c];
        }
        px = columns[POS_X];
        py = columns[POS_Y];
        pz = columns[POS_Z];
        vx = columns[VEL_X];
        vy = columns[VEL_Y];
        vz = columns[VEL_Z];
        halfWidth = columns[HALF_WIDTH];
        height = columns[HEIGHT];
        gravity = columns[GRAVITY];
        dragAirH = columns[DRAG_AIR_H];
        dragV = columns[DRAG_V];
        groundScale = columns[GROUND_SCALE];
        belowOffset = columns[BELOW_OFFSET];
        factorH = columns[FACTOR_H];
        factorV = columns[FACTOR_V];
        flags = new int[c];
        deferredList = new int[c];
        cellHash = new int[c];
        sorted = new int[c];
        buckets = new int[BodyLayout.bucketCount(c) + 1];
        this.pairs = new int[2 * this.pairCapacity];
        initPadding();
    }

    @Override
    public String engineName() {
        return JavaPhysicsKernel.NAME;
    }

    @Override
    double get(int column, int slot) {
        return columns[column][slot];
    }

    @Override
    void put(int column, int slot, double value) {
        columns[column][slot] = value;
    }

    @Override
    int getFlags(int slot) {
        return flags[slot];
    }

    @Override
    void putFlags(int slot, int value) {
        flags[slot] = value;
    }

    @Override
    void moveDeferred(int from, int to, int count) {
        System.arraycopy(deferredList, from, deferredList, to, count);
    }

    @Override
    HeapTerrain stepTerrainOf(TerrainView terrain) {
        return (HeapTerrain) terrain;
    }

    @Override
    int stepChunk(int begin, int end) {
        return JavaPhysicsKernel.step(this, stepTerrain, begin, end, stepMode);
    }

    @Override
    public int deferred(int k) {
        checkDeferred(k);
        return deferredList[k];
    }

    @Override
    public int broadphase(double margin, boolean stopAtCapacity) {
        return JavaPhysicsKernel.broadphase(this, size, margin, pairs, pairCapacity, stopAtCapacity);
    }

    @Override
    public int pairA(int k) {
        return pairs[2 * k];
    }

    @Override
    public int pairB(int k) {
        return pairs[2 * k + 1];
    }

    @Override
    public void close() {
        size = 0;
    }
}
