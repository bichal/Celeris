import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Many producers, one consumer: every producer publishes {@code items} sequence numbers and the
 * consumer must see each (producer, sequence) pair exactly once. Shared by the ring buffer and
 * result queue smoke tests.
 */
final class MpscStress {

    /** Tries to publish item {@code seq} of {@code producer}; false when full. */
    interface Publisher {
        boolean tryPublish(int producer, int seq);
    }

    /** Drains what is ready into {@code sink}; returns how many items it took. */
    interface Drainer {
        int drain(Sink sink);
    }

    interface Sink {
        void accept(int producer, int seq);

        default void acceptEncoded(long encoded) {
            accept((int) (encoded >>> 32), (int) (encoded & 0xFFFFFFFFL));
        }
    }

    record Result(long published, long consumed, long corrupted, long missing, long elapsedNanos) {

        void assertExact(int producers, int items, String path) {
            assertEquals(0, corrupted, "Detected corrupted or duplicated entries" + path);
            assertEquals(0, missing, "Some published entries were never consumed" + path);
            assertEquals((long) producers * items, published, "Unexpected published count");
        }

        void print(String what) {
            System.out.printf("%s: %d items in %.2f ms (%.0f ops/sec)%n", what, consumed,
                    elapsedNanos / 1_000_000.0, consumed / (elapsedNanos / 1_000_000_000.0));
        }
    }

    private MpscStress() {
    }

    static long encode(int producer, int seq) {
        return ((long) producer << 32) | (seq & 0xFFFFFFFFL);
    }

    /** Spin briefly, then yield. */
    static void backoff(int spins) {
        if (spins < 100) {
            Thread.onSpinWait();
        } else {
            Thread.yield();
        }
    }

    static Result run(int producers, int items, long timeoutMillis, Publisher publisher, Drainer drainer)
            throws InterruptedException {
        AtomicLongArray seen = new AtomicLongArray(producers * items);
        AtomicLong published = new AtomicLong();
        AtomicLong consumed = new AtomicLong();
        AtomicLong corrupted = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);

        Thread[] producerThreads = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            final int producer = p;
            producerThreads[p] = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < items; i++) {
                        for (int spins = 0; !publisher.tryPublish(producer, i); spins++) {
                            backoff(spins);
                        }
                        published.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        Sink sink = (producer, seq) -> {
            int index = producer * items + seq;
            if (index < 0 || index >= seen.length() || seen.getAndIncrement(index) != 0) {
                corrupted.incrementAndGet();
            }
        };
        long expected = (long) producers * items;
        Thread consumer = new Thread(() -> {
            int spins = 0;
            while (consumed.get() < expected && !Thread.currentThread().isInterrupted()) {
                int drained = drainer.drain(sink);
                if (drained > 0) {
                    spins = 0;
                    consumed.addAndGet(drained);
                } else {
                    backoff(spins++);
                }
            }
        });

        long startNanos = System.nanoTime();
        for (Thread t : producerThreads) {
            t.start();
        }
        consumer.start();
        start.countDown();
        for (Thread t : producerThreads) {
            t.join();
        }
        consumer.join(timeoutMillis);
        if (consumer.isAlive()) {
            consumer.interrupt();
            fail("Consumer thread did not finish within timeout; consumed=" + consumed.get()
                    + " published=" + published.get());
        }
        long elapsed = System.nanoTime() - startNanos;

        long missing = 0;
        for (int i = 0; i < seen.length(); i++) {
            if (seen.get(i) == 0) {
                missing++;
            }
        }
        return new Result(published.get(), consumed.get(), corrupted.get(), missing, elapsed);
    }
}
