import net.bichal.mods.celeris.physics.BodyBatch;
import net.bichal.mods.celeris.physics.ChunkWorkers;
import net.bichal.mods.celeris.physics.PhysicsFactory;
import net.bichal.mods.celeris.physics.PhysicsMode;
import net.bichal.mods.celeris.physics.PhysicsTestAccess;
import net.bichal.mods.celeris.physics.TerrainView;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ChunkWorkersTest {

    @Test
    void everyChunkRunsExactlyOnceAcrossManyRuns() {
        try (ChunkWorkers w = new ChunkWorkers(3)) {
            for (int run = 0; run < 2000; run++) {
                int n = 1 + run % 37;
                AtomicIntegerArray hits = new AtomicIntegerArray(n);
                w.run(hits::incrementAndGet, n);
                for (int c = 0; c < n; c++) {
                    assertEquals(1, hits.get(c), "run " + run + " chunk " + c);
                }
            }
        }
    }

    @Test
    void parallelStepEqualsSequentialStep() {
        // Above the parallel threshold, so the shared worker pool runs the chunks.
        int n = 20_000;
        PhysicsFactory f = PhysicsTestAccess.javaFactory();
        try (TerrainView t = PhysicsTestSupport.randomWorld(f, 21);
             BodyBatch parallel = f.newBatch(n, PhysicsMode.VANILLA, 0);
             BodyBatch serial = f.newBatch(n, PhysicsMode.VANILLA, 0)) {
            PhysicsTestSupport.randomBodies(parallel, n, 9);
            PhysicsTestSupport.randomBodies(serial, 1024, 9);
            for (int tick = 0; tick < 5; tick++) {
                parallel.step(t, 0);
                serial.step(t, 0);
            }
            // The first chunk of the big batch saw exactly the same bodies as the small one.
            for (int i = 0; i < 1024; i++) {
                assertEquals(serial.x(i), parallel.x(i));
                assertEquals(serial.vy(i), parallel.vy(i));
                assertEquals(serial.flags(i), parallel.flags(i));
            }
        }
    }

    @Test
    void reentrantRunIsRefusedAndTheInstanceStaysUsable() {
        for (int threads : new int[] {0, 3}) {
            try (ChunkWorkers w = new ChunkWorkers(threads)) {
                AtomicReference<Throwable> inner = new AtomicReference<>();
                AtomicBoolean innerTry = new AtomicBoolean(true);
                ChunkWorkers.ChunkTask nested = c -> {
                    if (c == 0) {
                        try {
                            w.run(x -> { }, 2);
                        } catch (Throwable e) {
                            inner.set(e);
                        }
                        innerTry.set(w.tryRun(x -> { }, 2));
                    }
                };
                // Single-threaded (inline) and pooled paths alike.
                w.run(nested, threads == 0 ? 1 : 8);
                assertInstanceOf(IllegalStateException.class, inner.get(), threads + " threads");
                assertFalse(innerTry.get());
                AtomicIntegerArray hits = new AtomicIntegerArray(5);
                w.run(hits::incrementAndGet, 5);
                for (int c = 0; c < 5; c++) {
                    assertEquals(1, hits.get(c));
                }
            }
        }
    }

    @Test
    void runsFromOtherThreadsShareThePoolUntilEverySlotIsTaken() throws Exception {
        try (ChunkWorkers w = new ChunkWorkers(2)) {
            int owners = ChunkWorkers.MAX_CONCURRENT_RUNS;
            CountDownLatch started = new CountDownLatch(owners);
            CountDownLatch release = new CountDownLatch(1);
            Thread[] threads = new Thread[owners];
            for (int o = 0; o < owners; o++) {
                // One chunk: it runs on the owner itself and holds the slot until released.
                threads[o] = new Thread(() -> w.run(c -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, 1));
                threads[o].start();
            }
            started.await();
            assertThrows(IllegalStateException.class, () -> w.run(c -> { }, 4));
            assertFalse(w.tryRun(c -> { }, 4));
            release.countDown();
            for (Thread t : threads) {
                t.join();
            }
            assertTrue(w.tryRun(c -> { }, 4));
        }
    }

    @Test
    void concurrentRunsEachRunEveryChunkOnce() throws Exception {
        try (ChunkWorkers w = new ChunkWorkers(3)) {
            int owners = 6; // more than the slots: some runs are refused and done by hand
            AtomicReference<Throwable> problem = new AtomicReference<>();
            Thread[] threads = new Thread[owners];
            for (int o = 0; o < owners; o++) {
                int seed = o;
                threads[o] = new Thread(() -> {
                    for (int run = 0; run < 500; run++) {
                        int n = 1 + (run * 7 + seed) % 41;
                        AtomicIntegerArray hits = new AtomicIntegerArray(n);
                        if (!w.tryRun(hits::incrementAndGet, n)) {
                            for (int c = 0; c < n; c++) {
                                hits.incrementAndGet(c);
                            }
                        }
                        for (int c = 0; c < n; c++) {
                            if (hits.get(c) != 1) {
                                problem.compareAndSet(null, new AssertionError(
                                        "owner " + seed + " run " + run + " chunk " + c + " ran " + hits.get(c) + " times"));
                            }
                        }
                    }
                });
                threads[o].start();
            }
            for (Thread t : threads) {
                t.join();
            }
            assertNull(problem.get());
        }
    }

    @Test
    void aFailingTaskReleasesTheGuard() {
        try (ChunkWorkers w = new ChunkWorkers(0)) {
            assertThrows(RuntimeException.class, () -> w.run(c -> {
                throw new RuntimeException("boom");
            }, 3));
            AtomicIntegerArray hits = new AtomicIntegerArray(3);
            w.run(hits::incrementAndGet, 3);
            assertEquals(1, hits.get(2));
        }
    }
}
