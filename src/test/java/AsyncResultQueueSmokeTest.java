import net.bichal.mods.celeris.framework.async.AsyncResultQueue;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Correctness coverage for {@link AsyncResultQueue}, specifically targeting
 * the reserveSlot/write-object/publishReserved ordering that makes the
 * side-channel {@code AtomicReferenceArray} safe without its own locking.
 * If that ordering were ever broken (e.g. someone "simplifies" offer() back
 * to tryPublishIndexed-then-write), this test should start failing with
 * either null results observed in drainTo or corrupted/duplicated payloads,
 * not silently pass.
 */
@SuppressWarnings("unused")
class AsyncResultQueueSmokeTest {

    @Test
    void handlesConcurrentProducersWithoutLossDuplicationOrNulls() throws Exception {
        int producers = 8;
        int itemsPerProducer = 150_000;
        // Small capacity on purpose: forces frequent slot reuse.
        try (AsyncResultQueue<WorkResult> queue = new AsyncResultQueue<>(1 << 6)) {
            AtomicLong nullsObserved = new AtomicLong();
            MpscStress.Result result = MpscStress.run(producers, itemsPerProducer, 10_000,
                    (p, i) -> queue.offer(new WorkResult(p, i)),
                    sink -> queue.drainTo(r -> {
                        if (r == null) {
                            nullsObserved.incrementAndGet();
                        } else {
                            sink.accept(r.producerId(), r.sequence());
                        }
                    }, 16));
            assertEquals(0, nullsObserved.get(),
                    "drainTo observed a null result -- reserveSlot/publishReserved ordering is broken");
            result.assertExact(producers, itemsPerProducer, "");
        }
    }

    @Test
    void offerRejectsNull() {
        try (AsyncResultQueue<String> queue = new AsyncResultQueue<>(1 << 4)) {
            assertTrue(assertThrowsNpe(queue), "offer(null) must throw NullPointerException");
        }
    }

    private boolean assertThrowsNpe(AsyncResultQueue<String> queue) {
        try {
            queue.offer(null);
            return false;
        } catch (NullPointerException expected) {
            return true;
        }
    }

    @Test
    void drainToReturnsZeroWhenEmpty() {
        try (AsyncResultQueue<String> queue = new AsyncResultQueue<>(1 << 4)) {
            int[] callCount = {0};
            int drained = queue.drainTo(r -> callCount[0]++, 8);
            assertEquals(0, drained, "Draining an empty queue must return 0, not block");
            assertEquals(0, callCount[0], "Consumer must not be called when nothing was drained");
        }
    }

    private record WorkResult(int producerId, int sequence) {
    }
}
