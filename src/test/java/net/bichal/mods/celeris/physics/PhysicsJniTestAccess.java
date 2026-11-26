package net.bichal.mods.celeris.physics;

/**
 * Lets the tests (default package, see src/test/java) pick the JNI engine and
 * pin its ISA. Not API.
 */
public final class PhysicsJniTestAccess {

    private PhysicsJniTestAccess() {
    }

    /** The JNI engine; throws if the library for this platform is not on the classpath. */
    public static PhysicsFactory jniFactory() {
        return new JniPhysicsFactory();
    }

    /** ISAs the native kernel can run on this CPU, by name. Restores the default afterwards. */
    public static String[] jniIsas() {
        return NativeKernel.supported(JniPhysicsKernel.ISA);
    }

    /** Pins the native ISA by name; returns the active ISA name. */
    public static String forceJniIsa(String isa) {
        return NativeKernel.force(JniPhysicsKernel.ISA, isa, false);
    }

    /** The engine family {@code CelerisPhysics} would try for a property value and a native request. */
    public static String enginePolicy(String property, boolean nativeRequested) {
        return CelerisPhysics.policy(property, nativeRequested);
    }
}
