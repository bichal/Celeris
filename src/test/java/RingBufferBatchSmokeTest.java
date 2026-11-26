import net.bichal.mods.celeris.core.concurrency.MpscRingBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Correctness coverage for {@link MpscRingBuffer#tryConsumeBatch}, mirroring
 * {@code RingBufferSmokeTest}'s coverage of {@code tryConsume}. This exists
 * because the batch-drain path exercises different bookkeeping (single
 * readCursor release per batch instead of per item, early-stop on the first
 * unpublished slot within the batch) and had no correctness test of its own
 * -- only a throughput benchmark, which does not assert on ordering,
 * duplication, or loss.
 */
@SuppressWarnings("unused")
class RingBufferBatchSmokeTest {

    @Test
    void tryConsumeBatchHandlesConcurrentProducersWithoutLossOrDuplication() throws Exception {
        int producers = 8;
        int itemsPerProducer = 150_000;
        int maxBatch = 16; // smaller than capacity: forces partial batches too
        // Small capacity on purpose: forces frequent wraparound.
        try (MpscRingBuffer ring = new MpscRingBuffer(1 << 6, Long.BYTES)) {
            long[] out = new long[maxBatch];
            MpscStress.Result result = MpscStress.run(producers, itemsPerProducer, 5_000,
                    (p, i) -> ring.tryPublish(MpscStress.encode(p, i)),
                    sink -> {
                        int drained = ring.tryConsumeBatch(out, 0, maxBatch);
                        for (int i = 0; i < drained; i++) {
                            sink.acceptEncoded(out[i]);
                        }
                        return drained;
                    });
            result.assertExact(producers, itemsPerProducer, " via tryConsumeBatch");
            result.print("MpscRingBuffer (batch=" + maxBatch + ", " + producers + " producers)");
        }
    }

    @Test
    void tryConsumeBatchReturnsZeroWhenEmpty() {
        try (MpscRingBuffer ring = new MpscRingBuffer(1 << 4, Long.BYTES)) {
            long[] out = new long[8];
            int drained = ring.tryConsumeBatch(out, 0, 8);
            assertEquals(0, drained, "Draining an empty ring must return 0, not block or throw");
        }
    }

    @Test
    void tryConsumeBatchStopsAtFirstGapAndReturnsPartialBatch() {
        try (MpscRingBuffer ring = new MpscRingBuffer(1 << 4, Long.BYTES)) {
            // Publish exactly 3 items; nothing after them is available yet.
            for (long v = 1; v <= 3; v++) {
                assertTrue(ring.tryPublish(v), "publish should succeed on empty ring");
            }

            long[] out = new long[10];
            int drained = ring.tryConsumeBatch(out, 0, 10);

            assertEquals(3, drained, "Batch must stop at the first unpublished slot, not block waiting for more");
            for (int i = 0; i < 3; i++) {
                assertEquals(i + 1L, out[i], "Item at batch position " + i + " does not match publish order");
            }
        }
    }
}
