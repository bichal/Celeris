package net.bichal.mods.celeris.physics;

import java.util.Arrays;

/**
 * The terrain model the three engines share: a window of {@code sizeX * sizeY * sizeZ} sections,
 * an int directory from section to page (-1 when unloaded), a growable pool of 4 KiB pages with a
 * free list, and a 256-entry float friction table. A subclass only stores those (Java arrays,
 * {@code Unsafe} memory or a {@code MemorySegment}) and keeps any native header in step.
 */
abstract class AbstractTerrain implements TerrainView {

    static final int PAGE = SECTION_BYTES;
    static final float DEFAULT_FRICTION = 0.6F;

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    final int sections;
    int originX;
    int originY;
    int originZ;
    private int pageCapacity;
    private int[] freePages = new int[0];
    private int freeCount;
    private int frictionClasses = 1;

    AbstractTerrain(int sizeX, int sizeY, int sizeZ) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || (long) sizeX * sizeY * sizeZ > (1 << 24)) {
            throw new IllegalArgumentException("terrain window out of range: " + sizeX + "x" + sizeY + "x" + sizeZ);
        }
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.sections = sizeX * sizeY * sizeZ;
    }

    /** Allocates the first pages and publishes the origin; call once the subclass's storage exists. */
    final void initPages() {
        growPages(Math.min(sections, 64));
        originChanged();
    }

    // ---------------------------------------------------------------- storage

    abstract int directory(int index);

    abstract void setDirectory(int index, int page);

    /** Every directory entry back to -1. */
    abstract void clearDirectory();

    abstract void copyToPage(int page, byte[] cells, int offset);

    abstract int pageByte(int page, int local);

    abstract void setPageByte(int page, int local, byte value);

    abstract void fillPage(int page, byte value);

    abstract float frictionAt(int cellClass);

    abstract void setFrictionAt(int cellClass, float value);

    /** Grows the page pool from {@code oldCapacity} to {@code newCapacity} pages, keeping their contents. */
    abstract void resizePages(int oldCapacity, int newCapacity);

    /** The origin changed (a native header mirrors it). */
    void originChanged() {
    }

    /** Throws once the terrain is closed, where an engine would touch freed memory. */
    void checkOpen() {
    }

    // ---------------------------------------------------------------- TerrainView

    @Override
    public int sizeX() {
        return sizeX;
    }

    @Override
    public int sizeY() {
        return sizeY;
    }

    @Override
    public int sizeZ() {
        return sizeZ;
    }

    @Override
    public int originX() {
        return originX;
    }

    @Override
    public int originY() {
        return originY;
    }

    @Override
    public int originZ() {
        return originZ;
    }

    @Override
    public void setOrigin(int sectionX, int sectionY, int sectionZ) {
        checkOpen();
        originX = sectionX;
        originY = sectionY;
        originZ = sectionZ;
        clearDirectory();
        freeCount = 0;
        for (int p = pageCapacity - 1; p >= 0; p--) {
            freePages[freeCount++] = p;
        }
        originChanged();
    }

    @Override
    public boolean covers(int sectionX, int sectionY, int sectionZ) {
        return dirIndex(sectionX, sectionY, sectionZ) >= 0;
    }

    @Override
    public void setSection(int sectionX, int sectionY, int sectionZ, byte[] cells, int offset) {
        checkOpen();
        if (offset < 0 || offset > cells.length - PAGE) {
            throw new IndexOutOfBoundsException("section needs " + PAGE + " bytes at " + offset + " of " + cells.length);
        }
        int page = pageFor(sectionX, sectionY, sectionZ, false);
        if (page >= 0) {
            copyToPage(page, cells, offset);
        }
    }

    @Override
    public void clearSection(int sectionX, int sectionY, int sectionZ) {
        checkOpen();
        int d = dirIndex(sectionX, sectionY, sectionZ);
        if (d < 0) {
            return;
        }
        int page = directory(d);
        if (page >= 0) {
            setDirectory(d, -1);
            freePages[freeCount++] = page;
        }
    }

    @Override
    public void setCell(int x, int y, int z, int cellClass) {
        checkOpen();
        int page = pageFor(x >> 4, y >> 4, z >> 4, true);
        if (page >= 0) {
            setPageByte(page, local(x, y, z), (byte) cellClass);
        }
    }

    @Override
    public int cell(int x, int y, int z) {
        checkOpen();
        int d = dirIndex(x >> 4, y >> 4, z >> 4);
        if (d < 0) {
            return CellClass.COMPLEX;
        }
        int page = directory(d);
        return page < 0 ? CellClass.COMPLEX : pageByte(page, local(x, y, z)) & 0xFF;
    }

    @Override
    public int solidClass(float value) {
        checkOpen();
        if (value == DEFAULT_FRICTION) {
            return CellClass.SOLID_DEFAULT;
        }
        for (int c = 2; c <= frictionClasses; c++) {
            if (Float.floatToRawIntBits(frictionAt(c)) == Float.floatToRawIntBits(value)) {
                return c;
            }
        }
        if (frictionClasses >= CellClass.LAST_SOLID) {
            throw new IllegalStateException("more than 253 distinct block friction values");
        }
        int c = ++frictionClasses;
        setFrictionAt(c, value);
        return c;
    }

    @Override
    public float friction(int cellClass) {
        checkOpen();
        return frictionAt(cellClass & 0xFF);
    }

    // ---------------------------------------------------------------- helpers

    /** Directory index of a section, or -1 outside the window. */
    final int dirIndex(int sectionX, int sectionY, int sectionZ) {
        int sx = sectionX - originX;
        int sy = sectionY - originY;
        int sz = sectionZ - originZ;
        if (Integer.compareUnsigned(sx, sizeX) >= 0 | Integer.compareUnsigned(sy, sizeY) >= 0
                | Integer.compareUnsigned(sz, sizeZ) >= 0) {
            return -1;
        }
        return (sy * sizeZ + sz) * sizeX + sx;
    }

    static int local(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    private int pageFor(int sectionX, int sectionY, int sectionZ, boolean fillComplex) {
        int d = dirIndex(sectionX, sectionY, sectionZ);
        if (d < 0) {
            return -1;
        }
        int page = directory(d);
        if (page >= 0) {
            return page;
        }
        if (freeCount == 0) {
            growPages(pageCapacity * 2);
        }
        page = freePages[--freeCount];
        if (fillComplex) {
            fillPage(page, (byte) CellClass.COMPLEX);
        }
        setDirectory(d, page);
        return page;
    }

    private void growPages(int newCapacity) {
        resizePages(pageCapacity, newCapacity);
        int[] free = Arrays.copyOf(freePages, newCapacity);
        for (int p = newCapacity - 1; p >= pageCapacity; p--) {
            free[freeCount++] = p;
        }
        freePages = free;
        pageCapacity = newCapacity;
    }
}
