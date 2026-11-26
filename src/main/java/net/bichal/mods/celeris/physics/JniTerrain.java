package net.bichal.mods.celeris.physics;

import static net.bichal.mods.celeris.physics.RawMemory.UNSAFE;

/**
 * {@link TerrainView} on raw off-heap memory for the JNI engine: the shared
 * terrain model in {@code Unsafe} memory, plus a {@code cp_terrain} header
 * pointing at the directory, pages and friction table, which is what the
 * native kernel receives. Same behaviour as {@code SegmentTerrain}; every
 * access throws once closed instead of touching freed memory.
 */
@SuppressWarnings("removal")
final class JniTerrain extends AbstractTerrain {

    // struct cp_terrain on the 64-bit targets Celeris ships natives for: three pointers, then six int32.
    private static final long H_DIRECTORY = 0;
    private static final long H_PAGES = 8;
    private static final long H_FRICTION = 16;
    private static final long H_ORIGIN_X = 24;
    private static final long H_SIZE_X = 36;
    private static final long HEADER_BYTES = 48;

    private final RawMemory header;
    private final RawMemory directory;
    private final RawMemory friction;
    private RawMemory pages;
    private boolean closed;

    JniTerrain(int sizeX, int sizeY, int sizeZ) {
        super(sizeX, sizeY, sizeZ);
        header = RawMemory.allocate(HEADER_BYTES, 64);
        directory = RawMemory.allocate((long) sections * Integer.BYTES, 64);
        clearDirectory();
        friction = RawMemory.allocate(256L * Float.BYTES, 64);
        for (int c = 0; c < 256; c++) {
            setFrictionAt(c, DEFAULT_FRICTION);
        }
        UNSAFE.putLong(header.address + H_DIRECTORY, directory.address);
        UNSAFE.putLong(header.address + H_FRICTION, friction.address);
        UNSAFE.putInt(header.address + H_SIZE_X, sizeX);
        UNSAFE.putInt(header.address + H_SIZE_X + 4, sizeY);
        UNSAFE.putInt(header.address + H_SIZE_X + 8, sizeZ);
        initPages();
    }

    /** Address of the {@code cp_terrain} header. */
    long header() {
        return header.address;
    }

    @Override
    void checkOpen() {
        if (closed) {
            throw new IllegalStateException("terrain closed");
        }
    }

    private long dirAddress(int index) {
        return directory.address + ((long) index << 2);
    }

    private long pageAddress(int page) {
        return pages.address + (long) page * PAGE;
    }

    @Override
    int directory(int index) {
        return UNSAFE.getInt(dirAddress(index));
    }

    @Override
    void setDirectory(int index, int page) {
        UNSAFE.putInt(dirAddress(index), page);
    }

    @Override
    void clearDirectory() {
        UNSAFE.setMemory(directory.address, directory.size, (byte) 0xFF); // every entry -1: unloaded
    }

    @Override
    void copyToPage(int page, byte[] cells, int offset) {
        UNSAFE.copyMemory(cells, RawMemory.BYTE_ARRAY_BASE + offset, null, pageAddress(page), PAGE);
    }

    @Override
    int pageByte(int page, int local) {
        return UNSAFE.getByte(pageAddress(page) + local);
    }

    @Override
    void setPageByte(int page, int local, byte value) {
        UNSAFE.putByte(pageAddress(page) + local, value);
    }

    @Override
    void fillPage(int page, byte value) {
        UNSAFE.setMemory(pageAddress(page), PAGE, value);
    }

    @Override
    float frictionAt(int cellClass) {
        return UNSAFE.getFloat(friction.address + ((long) cellClass << 2));
    }

    @Override
    void setFrictionAt(int cellClass, float value) {
        UNSAFE.putFloat(friction.address + ((long) cellClass << 2), value);
    }

    @Override
    void resizePages(int oldCapacity, int newCapacity) {
        // +64: the kernels never read past a page, but keep a cache line of slack anyway.
        RawMemory grown = RawMemory.allocate((long) newCapacity * PAGE + 64, 64);
        if (pages != null) {
            UNSAFE.copyMemory(pages.address, grown.address, (long) oldCapacity * PAGE);
            pages.free();
        }
        pages = grown;
        UNSAFE.putLong(header.address + H_PAGES, pages.address);
    }

    @Override
    void originChanged() {
        UNSAFE.putInt(header.address + H_ORIGIN_X, originX);
        UNSAFE.putInt(header.address + H_ORIGIN_X + 4, originY);
        UNSAFE.putInt(header.address + H_ORIGIN_X + 8, originZ);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        pages.free();
        directory.free();
        friction.free();
        header.free();
    }
}
