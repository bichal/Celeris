package net.bichal.mods.celeris.physics;

import java.util.Arrays;

/**
 * {@link TerrainView} on Java arrays, for the pure-Java engine (no FFM, no
 * JVM flags): the directory, the page pool in one growable {@code byte[]} and
 * the friction table are plain arrays that {@link JavaPhysicsKernel} reads directly.
 */
final class HeapTerrain extends AbstractTerrain {

    final int[] directory;
    final float[] friction = new float[256];
    byte[] pages = new byte[0];

    HeapTerrain(int sizeX, int sizeY, int sizeZ) {
        super(sizeX, sizeY, sizeZ);
        directory = new int[sections];
        Arrays.fill(directory, -1);
        Arrays.fill(friction, DEFAULT_FRICTION);
        initPages();
    }

    @Override
    int directory(int index) {
        return directory[index];
    }

    @Override
    void setDirectory(int index, int page) {
        directory[index] = page;
    }

    @Override
    void clearDirectory() {
        Arrays.fill(directory, -1);
    }

    @Override
    void copyToPage(int page, byte[] cells, int offset) {
        System.arraycopy(cells, offset, pages, page * PAGE, PAGE);
    }

    @Override
    int pageByte(int page, int local) {
        return pages[page * PAGE + local];
    }

    @Override
    void setPageByte(int page, int local, byte value) {
        pages[page * PAGE + local] = value;
    }

    @Override
    void fillPage(int page, byte value) {
        Arrays.fill(pages, page * PAGE, (page + 1) * PAGE, value);
    }

    @Override
    float frictionAt(int cellClass) {
        return friction[cellClass];
    }

    @Override
    void setFrictionAt(int cellClass, float value) {
        friction[cellClass] = value;
    }

    @Override
    void resizePages(int oldCapacity, int newCapacity) {
        if ((long) newCapacity * PAGE > Integer.MAX_VALUE - 64) {
            throw new IllegalStateException("terrain page pool exhausted");
        }
        pages = Arrays.copyOf(pages, newCapacity * PAGE);
    }

    @Override
    public void close() {
        pages = new byte[0];
        Arrays.fill(directory, -1);
    }
}
