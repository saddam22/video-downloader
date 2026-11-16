package com.wan.downloadmanager.engine;

import java.util.ArrayDeque;
import java.util.Deque;

/** Bytes/second over a sliding window of recent samples (real transfer only — never estimated). */
public class SpeedMeter {
    private static final long WINDOW_NANOS = 3_000_000_000L;
    private final Deque<long[]> samples = new ArrayDeque<>();
    private long total;

    public synchronized void add(long bytes) {
        long now = System.nanoTime();
        samples.addLast(new long[]{now, bytes});
        total += bytes;
        trim(now);
    }

    public synchronized long bytesPerSecond() {
        long now = System.nanoTime();
        trim(now);
        if (samples.isEmpty()) return 0;
        long span = Math.max(now - samples.peekFirst()[0], 500_000_000L);
        long sum = 0;
        for (long[] s : samples) sum += s[1];
        return sum * 1_000_000_000L / span;
    }

    public synchronized long total() {
        return total;
    }

    private void trim(long now) {
        while (!samples.isEmpty() && now - samples.peekFirst()[0] > WINDOW_NANOS) samples.removeFirst();
    }
}
