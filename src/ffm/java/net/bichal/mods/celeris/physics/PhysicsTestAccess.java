package net.bichal.mods.celeris.physics;

/**
 * Lets the tests (default package, see src/test/java) pick a specific engine
 * instead of the auto-selected one. Not API.
 */
@SuppressWarnings({"Since15", "preview", "RedundantSuppression"})
public final class PhysicsTestAccess {

    private PhysicsTestAccess() {
    }

    /** The pure-Java reference engine (heap arrays). */
    public static PhysicsFactory javaFactory() {
        return new HeapPhysicsFactory();
    }

    /** The native engine; throws if the library for this platform is not on the classpath. */
    public static PhysicsFactory nativeFactory() {
        return new SegmentPhysicsFactory();
    }

    /** ISAs the native kernel can run on this CPU, by name. Restores the default afterwards. */
    public static String[] nativeIsas(PhysicsFactory nativeFactory) {
        return NativeKernel.supported(kernel(nativeFactory));
    }

    /** Pins the native ISA by name; returns the active ISA name. */
    public static String forceNativeIsa(PhysicsFactory nativeFactory, String isa) {
        return NativeKernel.force(kernel(nativeFactory), isa, false);
    }

    private static NativePhysicsKernel kernel(PhysicsFactory nativeFactory) {
        return (NativePhysicsKernel) ((SegmentPhysicsFactory) nativeFactory).kernel();
    }
}
