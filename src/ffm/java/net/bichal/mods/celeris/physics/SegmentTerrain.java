package net.bichal.mods.celeris.physics;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

/**
 * {@link TerrainView} on off-heap memory: the shared terrain model in
 * {@code MemorySegment}s, plus a {@code cp_terrain} header struct pointing at
 * the directory, pages and friction table, which is what the native kernel receives.
 */
@SuppressWarnings({"Since15", "preview", "RedundantSuppression"})
final class SegmentTerrain extends AbstractTerrain {

    /** Mirrors {@code struct cp_terrain}; natural alignment, 48 bytes. */
    static final StructLayout HEADER = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("directory"),
            ValueLayout.ADDRESS.withName("pages"),
            ValueLayout.ADDRESS.withName("friction"),
            ValueLayout.JAVA_INT.withName("origin_x"),
            ValueLayout.JAVA_INT.withName("origin_y"),
            ValueLayout.JAVA_INT.withName("origin_z"),
            ValueLayout.JAVA_INT.withName("size_x"),
            ValueLayout.JAVA_INT.withName("size_y"),
            ValueLayout.JAVA_INT.withName("size_z"));

    private static final long H_PAGES = HEADER.byteOffset(groupElement("pages"));
    private static final long H_ORIGIN_X = HEADER.byteOffset(groupElement("origin_x"));

    private final Arena arena = Arena.ofShared();
    final MemorySegment header;
    final MemorySegment directory;
    final MemorySegment friction;
    MemorySegment pages;
    private Arena pagesArena;
    private boolean closed;

    SegmentTerrain(int sizeX, int sizeY, int sizeZ) {
        super(sizeX, sizeY, sizeZ);
        header = arena.allocate(HEADER);
        directory = arena.allocate((long) sections * Integer.BYTES, 64);
        clearDirectory();
        friction = arena.allocate(256L * Float.BYTES, 64);
        for (int c = 0; c < 256; c++) {
            setFrictionAt(c, DEFAULT_FRICTION);
        }
        header.set(ValueLayout.ADDRESS, HEADER.byteOffset(groupElement("directory")), directory);
        header.set(ValueLayout.ADDRESS, HEADER.byteOffset(groupElement("friction")), friction);
        header.set(ValueLayout.JAVA_INT, HEADER.byteOffset(groupElement("size_x")), sizeX);
        header.set(ValueLayout.JAVA_INT, HEADER.byteOffset(groupElement("size_y")), sizeY);
        header.set(ValueLayout.JAVA_INT, HEADER.byteOffset(groupElement("size_z")), sizeZ);
        initPages();
    }

    @Override
    int directory(int index) {
        return directory.getAtIndex(ValueLayout.JAVA_INT, index);
    }

    @Override
    void setDirectory(int index, int page) {
        directory.setAtIndex(ValueLayout.JAVA_INT, index, page);
    }

    @Override
    void clearDirectory() {
        directory.fill((byte) 0xFF); // every entry -1: unloaded
    }

    @Override
    void copyToPage(int page, byte[] cells, int offset) {
        MemorySegment.copy(cells, offset, pages, ValueLayout.JAVA_BYTE, (long) page * PAGE, PAGE);
    }

    @Override
    int pageByte(int page, int local) {
        return pages.get(ValueLayout.JAVA_BYTE, (long) page * PAGE + local);
    }

    @Override
    void setPageByte(int page, int local, byte value) {
        pages.set(ValueLayout.JAVA_BYTE, (long) page * PAGE + local, value);
    }

    @Override
    void fillPage(int page, byte value) {
        pages.asSlice((long) page * PAGE, PAGE).fill(value);
    }

    @Override
    float frictionAt(int cellClass) {
        return friction.getAtIndex(ValueLayout.JAVA_FLOAT, cellClass);
    }

    @Override
    void setFrictionAt(int cellClass, float value) {
        friction.setAtIndex(ValueLayout.JAVA_FLOAT, cellClass, value);
    }

    @Override
    void resizePages(int oldCapacity, int newCapacity) {
        Arena next = Arena.ofShared();
        // +64: the kernels never read past a page, but keep a cache line of slack anyway.
        MemorySegment grown = next.allocate((long) newCapacity * PAGE + 64, 64);
        if (pages != null) {
            MemorySegment.copy(pages, 0, grown, 0, (long) oldCapacity * PAGE);
            pagesArena.close();
        }
        pages = grown;
        pagesArena = next;
        header.set(ValueLayout.ADDRESS, H_PAGES, pages);
    }

    @Override
    void originChanged() {
        header.set(ValueLayout.JAVA_INT, H_ORIGIN_X, originX);
        header.set(ValueLayout.JAVA_INT, H_ORIGIN_X + 4, originY);
        header.set(ValueLayout.JAVA_INT, H_ORIGIN_X + 8, originZ);
    }

    @Override
    public void close() {
        if (closed) {
            return; // idempotent, like the heap and JNI terrains
        }
        closed = true;
        pagesArena.close();
        arena.close();
    }
}
