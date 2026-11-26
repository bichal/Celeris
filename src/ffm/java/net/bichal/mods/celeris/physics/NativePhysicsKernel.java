package net.bichal.mods.celeris.physics;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Downcalls into {@code libceleris_physics} (see {@code native/}). One
 * {@code cp_step} call simulates a whole work unit ({@link BodyLayout#MIN_UNIT}
 * to {@link BodyLayout#CHUNK} bodies) -- gravity, collision and drag fused while
 * the unit sits in L1/L2 -- so the fixed cost of a downcall (a few ns) is paid
 * once per hundreds of bodies.
 *
 * <p>Both hot entry points are linked as critical/trivial: no thread-state
 * transition and no safepoint poll. A chunk call runs tens of microseconds,
 * which is the longest a GC safepoint can be held up by it; {@link
 * BodyLayout#CHUNK} bounds that.
 */
@SuppressWarnings({"Since15", "preview", "RedundantSuppression"})
final class NativePhysicsKernel implements PhysicsKernel, NativeKernel.Control {

    private static final Linker LINKER = Linker.nativeLinker();
    //? >=25 {
    //private static final Linker.Option[] CRITICAL = { Linker.Option.critical(false) };
    //?} else
    private static final Linker.Option[] CRITICAL = { Linker.Option.isTrivial() };

    private final MethodHandle step;
    private final MethodHandle broadphase;
    private final MethodHandle activeIsa;
    private final MethodHandle forceIsa;

    NativePhysicsKernel() {
        SymbolLookup lib = SymbolLookup.libraryLookup(NativeKernel.extractLibrary(), Arena.global());
        MethodHandle abi = LINKER.downcallHandle(lib.find("cp_abi_version").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT), CRITICAL);
        MethodHandle signature = LINKER.downcallHandle(lib.find("cp_layout_signature").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT), CRITICAL);
        activeIsa = LINKER.downcallHandle(lib.find("cp_active_isa").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT), CRITICAL);
        forceIsa = LINKER.downcallHandle(lib.find("cp_force_isa").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        step = LINKER.downcallHandle(lib.find("cp_step").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                CRITICAL);
        broadphase = LINKER.downcallHandle(lib.find("cp_broadphase").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_DOUBLE,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                CRITICAL);
        try {
            NativeKernel.checkAbi((int) abi.invokeExact(), (int) signature.invokeExact());
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        NativeKernel.applyRequested(this);
    }

    @Override
    public String name() {
        return "native-" + NativeKernel.activeName(this);
    }

    @Override
    public int activeIsa() {
        try {
            return (int) activeIsa.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    @Override
    public int forceIsa(int isa) {
        try {
            return (int) forceIsa.invokeExact(isa);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    @Override
    public int step(MemorySegment slab, int capacity, SegmentTerrain terrain, int begin, int end, int mode) {
        int deferred;
        try {
            deferred = (int) step.invokeExact(slab, capacity, terrain.header, begin, end, mode);
        } catch (Throwable t) {
            throw new IllegalStateException("cp_step failed", t);
        }
        return NativeKernel.checkStep(deferred, begin, end, capacity);
    }

    @Override
    public int broadphase(MemorySegment slab, int capacity, int count, MemorySegment buckets, double margin,
                          MemorySegment pairs, int pairCapacity, boolean stopAtCapacity) {
        int found;
        try {
            found = (int) broadphase.invokeExact(slab, capacity, count, buckets, (int) (buckets.byteSize() / Integer.BYTES),
                    margin, pairs, pairCapacity, NativeKernel.broadphaseFlags(stopAtCapacity));
        } catch (Throwable t) {
            throw new IllegalStateException("cp_broadphase failed", t);
        }
        return NativeKernel.checkBroadphase(found, count, capacity);
    }
}
