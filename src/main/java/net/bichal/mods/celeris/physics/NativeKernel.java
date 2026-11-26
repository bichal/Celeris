package net.bichal.mods.celeris.physics;

import net.bichal.mods.celeris.util.NativeLibraries;

import java.nio.file.Path;

/**
 * What the JNI and FFM bindings of {@code libceleris_physics} share: the library file, the ABI
 * check, result checks, the ISA names and the {@code celeris.physics.isa} override.
 */
final class NativeKernel {

    /** CP_BROADPHASE_STOP_AT_CAPACITY. */
    private static final int STOP_AT_CAPACITY = 1;

    static final String[] NAMES = {"generic", "avx2", "avx512", "neon"};

    /** A loaded kernel's ISA controls. */
    interface Control {
        int activeIsa();

        /** Pins an ISA. Returns the ISA now active; unsupported requests are ignored. */
        int forceIsa(int isa);
    }

    private NativeKernel() {
    }

    /** Extracts the library for this platform and returns its path. */
    static Path extractLibrary() {
        return NativeLibraries.extract(NativeLibraries.isWindows() ? "celeris_physics.dll"
                : NativeLibraries.isMac() ? "libceleris_physics.dylib"
                : "libceleris_physics.so");
    }

    /** Throws unless the library was built for this {@link BodyLayout}. */
    static void checkAbi(int version, int layout) {
        if (version != BodyLayout.ABI_VERSION || layout != BodyLayout.signature()) {
            throw new IllegalStateException("celeris_physics ABI " + version + "/0x" + Integer.toHexString(layout)
                    + " does not match Java " + BodyLayout.ABI_VERSION + "/0x" + Integer.toHexString(BodyLayout.signature()));
        }
    }

    static int broadphaseFlags(boolean stopAtCapacity) {
        return stopAtCapacity ? STOP_AT_CAPACITY : 0;
    }

    /** {@code cp_step}'s result: the deferred count, or a rejection. */
    static int checkStep(int deferred, int begin, int end, int capacity) {
        if (deferred < 0) {
            throw new IllegalArgumentException("cp_step rejected chunk [" + begin + ", " + end + ") of capacity " + capacity);
        }
        return deferred;
    }

    /** {@code cp_broadphase}'s result: the pair count, or a rejection. */
    static int checkBroadphase(int found, int count, int capacity) {
        if (found < 0) {
            throw new IllegalArgumentException("cp_broadphase rejected count " + count + " of capacity " + capacity);
        }
        return found;
    }

    /** Applies {@code -Dceleris.physics.isa}, if set. */
    static void applyRequested(Control kernel) {
        String isa = System.getProperty("celeris.physics.isa");
        if (isa != null) {
            force(kernel, isa, true);
        }
    }

    static String activeName(Control kernel) {
        return NAMES[kernel.activeIsa()];
    }

    /** ISAs the kernel can run on this CPU, by name. Restores the active one afterwards. */
    static String[] supported(Control kernel) {
        int active = kernel.activeIsa();
        StringBuilder out = new StringBuilder();
        for (int isa = 0; isa < NAMES.length; isa++) {
            if (kernel.forceIsa(isa) == isa) {
                out.append(out.length() == 0 ? "" : ",").append(NAMES[isa]);
            }
        }
        kernel.forceIsa(active);
        return out.toString().split(",");
    }

    /** Pins the ISA called {@code name}; returns the active ISA name. */
    static String force(Control kernel, String name, boolean ignoreCase) {
        for (int k = 0; k < NAMES.length; k++) {
            if (ignoreCase ? NAMES[k].equalsIgnoreCase(name) : NAMES[k].equals(name)) {
                kernel.forceIsa(k);
            }
        }
        return activeName(kernel);
    }
}
