package net.bichal.mods.celeris.physics;


/**
 * Calls into {@code libceleris_physics} (see {@code native/}) over JNI
 * ({@code native/src/cp_jni.cpp}). Same kernel and same results as the FFM
 * binding ({@code NativePhysicsKernel}), but JNI needs neither FFM nor any
 * launch flag, and is not blocked by {@code --enable-native-access}; that is
 * why it is tried first. One {@code nativeStep} call simulates a whole work
 * unit ({@link BodyLayout#MIN_UNIT} to {@link BodyLayout#CHUNK} bodies), so the
 * ~20 ns JNI transition is paid once per hundreds of bodies. A thread inside
 * the call is in native state, which GC safepoints do not wait for.
 *
 * <p>Loading the class loads the library and checks its ABI; it fails with an
 * {@link UnsatisfiedLinkError} (or an {@link ExceptionInInitializerError}
 * around it) when this platform has no library, and {@link CelerisPhysics}
 * then tries the next engine.
 */
final class JniPhysicsKernel {

    /** ISA controls for {@link NativeKernel}. */
    static final NativeKernel.Control ISA = new NativeKernel.Control() {
        @Override
        public int activeIsa() {
            return nativeActiveIsa();
        }

        @Override
        public int forceIsa(int isa) {
            return nativeForceIsa(isa);
        }
    };

    static {
        System.load(NativeKernel.extractLibrary().toAbsolutePath().toString());
        NativeKernel.checkAbi(nativeAbiVersion(), nativeLayoutSignature());
        // JNI binds lazily: call every entry point once, with null arguments the kernel
        // rejects (-1) without touching memory, so a library that lacks a symbol fails
        // here (and selection falls back) instead of at the first tick.
        if (nativeStep(0L, 0, 0L, 0, 0, 0) != -1
                || nativeBroadphase(0L, 0, 0, 0L, 0, 0.0, 0L, 0, 0) != -1) {
            throw new IllegalStateException("celeris_physics accepted a null call; refusing to use it");
        }
        nativeActiveIsa();
        RawMemory.probe();
        NativeKernel.applyRequested(ISA);
    }

    private JniPhysicsKernel() {
    }

    static String name() {
        return "native-jni-" + NativeKernel.activeName(ISA);
    }

    static int step(long slab, int capacity, long terrain, int begin, int end, int mode) {
        return NativeKernel.checkStep(nativeStep(slab, capacity, terrain, begin, end, mode), begin, end, capacity);
    }

    static int broadphase(long slab, int capacity, int count, long buckets, int bucketInts, double margin,
                          long pairs, int pairCapacity, boolean stopAtCapacity) {
        return NativeKernel.checkBroadphase(nativeBroadphase(slab, capacity, count, buckets, bucketInts, margin, pairs,
                pairCapacity, NativeKernel.broadphaseFlags(stopAtCapacity)), count, capacity);
    }

    private static native int nativeAbiVersion();

    private static native int nativeLayoutSignature();

    private static native int nativeActiveIsa();

    private static native int nativeForceIsa(int isa);

    private static native int nativeStep(long bodies, int capacity, long terrain, int begin, int end, int mode);

    private static native int nativeBroadphase(long bodies, int capacity, int count, long buckets, int bucketInts,
                                               double margin, long pairs, int pairCapacity, int flags);
}
