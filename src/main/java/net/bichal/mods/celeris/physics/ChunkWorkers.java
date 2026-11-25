package net.bichal.mods.celeris.physics;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;

/**
 * Minimal fork-join for chunked batch work with zero allocation per run:
 * persistent daemon threads, a shared chunk counter claimed with one atomic
 * increment per chunk (dynamic load balancing -- collision cost varies a lot
 * between chunks), and the calling thread working alongside. Unlike a
 * {@code ForkJoinPool}, submitting a run creates no task objects.
 *
 * <p>Up to {@link #MAX_CONCURRENT_RUNS} runs from different threads share the
 * workers at once, each in a slot of its own: its task, chunk count, generation
 * and claim and done counters. A worker drains whichever slots have chunks left,
 * starting from a slot picked by its index so concurrent runs each get some
 * workers straight away, and every caller drains its own slot, so a run finishes
 * even when the workers are busy elsewhere. A run started while every slot is
 * taken is refused ({@link #tryRun} returns {@code false}), and so is a run
 * started from inside a chunk task, which would wait on the very workers it runs
 * on.
 *
 * <p>Each slot's claim counter carries the run's generation in its high 32 bits,
 * so a worker still finishing the previous run of that slot can never claim a
 * chunk of the next one with a stale task. Every counter sits on a cache line of
 * its own in one {@link AtomicLongArray}.
 */
public final class ChunkWorkers implements AutoCloseable {

    /** Work for one chunk index. Implement it once (e.g. on the batch); never capture per call. */
    @FunctionalInterface
    public interface ChunkTask {
        void run(int chunk);
    }

    /** Runs that can be in progress at once, from different threads. */
    public static final int MAX_CONCURRENT_RUNS = 4;

    /** Longs per slot in {@link #counters}: the claim and done counters 64 bytes apart, and apart from the next slot. */
    private static final int STRIDE = 16;
    /** How long a worker keeps polling for the next run before it parks (a step and a broadphase, or several levels, per tick). */
    private static final long SPIN_NANOS = Long.getLong("celeris.physics.workerSpinNanos", 30_000L);

    /** Whether the current thread is inside a run, as its caller or running one of its chunks. */
    private static final ThreadLocal<boolean[]> IN_RUN = ThreadLocal.withInitial(() -> new boolean[1]);

    private final Thread[] threads;
    /** Slot s: claim counter at {@code 8 + s * STRIDE}, done counter 8 longs after it. */
    private final AtomicLongArray counters = new AtomicLongArray(8 + MAX_CONCURRENT_RUNS * STRIDE);
    /** 1 while slot s holds a run (including a single-threaded one); claimed by CAS. */
    private final AtomicIntegerArray busy = new AtomicIntegerArray(MAX_CONCURRENT_RUNS);
    private final ChunkTask[] tasks = new ChunkTask[MAX_CONCURRENT_RUNS];
    private final int[] chunks = new int[MAX_CONCURRENT_RUNS];
    private final Throwable[] failures = new Throwable[MAX_CONCURRENT_RUNS];
    /** Slot s's generation; the volatile write that publishes its task and chunk count. */
    private final AtomicIntegerArray generations = new AtomicIntegerArray(MAX_CONCURRENT_RUNS);
    /** Bumped after every run is published, so a waiting worker notices any of them. */
    private final AtomicInteger signal = new AtomicInteger();
    private volatile boolean closed;

    public ChunkWorkers(int threadCount) {
        threads = new Thread[Math.max(0, threadCount)];
        for (int i = 0; i < threads.length; i++) {
            int first = i % MAX_CONCURRENT_RUNS;
            Thread t = new Thread(() -> workerLoop(first), "Celeris Physics Worker #" + i);
            t.setDaemon(true);
            threads[i] = t;
            t.start();
        }
    }

    public int threadCount() {
        return threads.length;
    }

    /**
     * Runs {@code task} for every chunk in {@code [0, chunkCount)} and returns
     * when all are done.
     *
     * @throws IllegalStateException if the run cannot start: every slot is taken
     *         by runs from other threads, or this is called from inside a chunk task
     */
    public void run(ChunkTask task, int chunkCount) {
        if (!tryRun(task, chunkCount)) {
            throw new IllegalStateException("ChunkWorkers.run called while " + MAX_CONCURRENT_RUNS
                    + " other runs are in progress, or from inside a chunk task");
        }
    }

    /**
     * {@link #run}, unless it cannot start (every slot taken, or called from
     * inside a chunk task): then nothing runs and this returns {@code false}, so
     * the caller can do the chunks itself.
     */
    public boolean tryRun(ChunkTask task, int chunkCount) {
        if (chunkCount <= 0) {
            return true;
        }
        boolean[] inRun = IN_RUN.get();
        if (inRun[0]) {
            return false;
        }
        int slot = -1;
        for (int s = 0; s < MAX_CONCURRENT_RUNS; s++) {
            if (busy.get(s) == 0 && busy.compareAndSet(s, 0, 1)) {
                slot = s;
                break;
            }
        }
        if (slot < 0) {
            return false;
        }
        inRun[0] = true;
        try {
            runInSlot(slot, task, chunkCount);
        } finally {
            inRun[0] = false;
            busy.set(slot, 0);
        }
        return true;
    }

    private void runInSlot(int slot, ChunkTask task, int chunkCount) {
        if (threads.length == 0 || chunkCount == 1) {
            for (int c = 0; c < chunkCount; c++) {
                task.run(c);
            }
            return;
        }
        int next = 8 + slot * STRIDE;
        int done = next + 8;
        failures[slot] = null;
        int gen = generations.get(slot) + 1;
        // Retag the claim counter BEFORE publishing the new task: a worker
        // that read the previous generation may already be about to pick up
        // the new task/chunks, and must then find a tag it does not match
        // rather than the previous run's exhausted counter.
        counters.set(next, (long) gen << 32);
        counters.set(done, 0);
        tasks[slot] = task;
        chunks[slot] = chunkCount;
        generations.set(slot, gen); // volatile write publishes task/chunks/counters
        signal.incrementAndGet();
        // The calling thread takes chunks too: wake no more workers than there
        // are other chunks for (a parked worker costs a futex wake, a spinning
        // one notices the new signal by itself).
        for (int i = 0, n = Math.min(threads.length, chunkCount - 1); i < n; i++) {
            LockSupport.unpark(threads[i]);
        }
        drain(slot, task, chunkCount, gen);
        int spins = 0;
        while (counters.get(done) < chunkCount) {
            if (++spins < 1 << 12) {
                Thread.onSpinWait();
            } else {
                Thread.yield();
            }
        }
        tasks[slot] = null;
        Throwable t = failures[slot];
        if (t != null) {
            throw new IllegalStateException("Celeris physics worker failed", t);
        }
    }

    /** Runs chunks of slot {@code slot}'s run {@code gen} until none are left; whether it ran any. */
    private boolean drain(int slot, ChunkTask t, int n, int gen) {
        int next = 8 + slot * STRIDE;
        boolean ran = false;
        while (true) {
            long v = counters.get(next);
            int c = (int) v;
            if ((int) (v >>> 32) != gen || c >= n) {
                return ran;
            }
            if (!counters.compareAndSet(next, v, v + 1)) {
                continue;
            }
            ran = true;
            try {
                t.run(c);
            } catch (Throwable e) {
                failures[slot] = e;
            } finally {
                counters.incrementAndGet(next + 8);
            }
        }
    }

    private void workerLoop(int first) {
        // Chunks run here belong to someone else's run: a nested run from one would wait on this pool.
        IN_RUN.get()[0] = true;
        while (!closed) {
            int seen = signal.get();
            boolean ran = false;
            for (int i = 0; i < MAX_CONCURRENT_RUNS; i++) {
                int slot = (first + i) % MAX_CONCURRENT_RUNS;
                int gen = generations.get(slot); // read before the task: see runInSlot
                ChunkTask t = tasks[slot];
                int n = chunks[slot];
                if (t != null && drain(slot, t, n, gen)) {
                    ran = true;
                }
            }
            if (ran || signal.get() != seen) {
                continue;
            }
            // Poll briefly first: back-to-back runs then start without a wake-up.
            long until = System.nanoTime() + SPIN_NANOS;
            while (signal.get() == seen && !closed && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            if (signal.get() == seen && !closed) {
                LockSupport.park(this);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        for (Thread t : threads) {
            LockSupport.unpark(t);
        }
    }
}
