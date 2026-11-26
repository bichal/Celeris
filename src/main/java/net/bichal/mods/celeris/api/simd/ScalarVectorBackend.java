package net.bichal.mods.celeris.api.simd;

/**
 * Pure-Java scalar fallback, used when {@code jdk.incubator.vector} isn't
 * usable on this JVM (missing {@code --add-modules=jdk.incubator.vector}).
 * Same semantics as {@code SimdVectorBackend} for every method, just without
 * the lane-width parallelism -- correctness-equivalent, throughput-inferior,
 * exactly the same tradeoff {@code HeapMemoryBackend} makes for memory.
 *
 * <p>Only the original five abstract operations are implemented here; every
 * later operation on {@link VectorBackend} is declared with a scalar
 * {@code default} body, which <em>is</em> this backend's implementation.
 * Keep it that way: one scalar reference per operation, in one place.
 */
final class ScalarVectorBackend implements VectorBackend {

    // No real hardware lane width applies to a scalar loop; 1 keeps this
    // an honest answer for any caller that sizes work batches off of it
    // (a batch size of "1 lane" degrades to per-element processing, which
    // is exactly what this backend does anyway).
    private static final int SCALAR_LANE_COUNT = 1;

    @Override
    public int preferredLaneCount() {
        return SCALAR_LANE_COUNT;
    }

    @Override
    public void addInPlace(float[] a, float[] b, int length) {
        add(a, b, 0, length);
    }

    @Override
    public void subtractInPlace(float[] a, float[] b, int length) {
        subtract(a, b, 0, length);
    }

    @Override
    public float dotProduct(float[] a, float[] b, int length) {
        return dot(a, b, 0, length, 0f);
    }

    @Override
    public void thresholdMask(float[] values, boolean[] outMask, int length, float threshold) {
        threshold(values, outMask, 0, length, threshold);
    }

    @Override
    public void greaterThanMask(float[] a, float[] b, boolean[] outMask, int length) {
        greaterThan(a, b, outMask, 0, length);
    }

    @Override
    public String name() {
        return net.bichal.mods.celeris.core.memory.backend.CelerisRuntime.isCompatModeForced() ? "scalar (compat mode)" : "scalar";
    }

    // Element ranges [from, to): this backend's bodies and the SIMD backend's tails.

    static void add(float[] a, float[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            a[i] += b[i];
        }
    }

    static void subtract(float[] a, float[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            a[i] -= b[i];
        }
    }

    static float dot(float[] a, float[] b, int from, int to, float sum) {
        for (int i = from; i < to; i++) {
            sum = Math.fma(a[i], b[i], sum);
        }
        return sum;
    }

    static void threshold(float[] values, boolean[] outMask, int from, int to, float threshold) {
        for (int i = from; i < to; i++) {
            outMask[i] = values[i] > threshold;
        }
    }

    static void greaterThan(float[] a, float[] b, boolean[] outMask, int from, int to) {
        for (int i = from; i < to; i++) {
            outMask[i] = a[i] > b[i];
        }
    }
}
