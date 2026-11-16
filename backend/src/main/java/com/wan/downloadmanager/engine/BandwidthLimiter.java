package com.wan.downloadmanager.engine;

/**
 * Token-bucket rate limiter shared by all connections it governs. A rate of 0 means unlimited.
 * The bucket holds at most one second of tokens, so bursts stay short.
 */
public class BandwidthLimiter {
    private long rateBps;
    private double tokens;
    private long lastRefillNanos = System.nanoTime();

    public BandwidthLimiter(long rateBps) {
        this.rateBps = Math.max(0, rateBps);
    }

    public synchronized void setRate(long rateBps) {
        this.rateBps = Math.max(0, rateBps);
        this.tokens = Math.min(tokens, this.rateBps);
        notifyAll();
    }

    public synchronized long rate() {
        return rateBps;
    }

    /** Blocks until {@code bytes} may be transferred. */
    public void acquire(int bytes) throws InterruptedException {
        long remaining = bytes;
        while (remaining > 0) {
            synchronized (this) {
                if (rateBps == 0) return;
                refill();
                if (tokens >= 1) {
                    long take = (long) Math.min(remaining, tokens);
                    tokens -= take;
                    remaining -= take;
                    continue;
                }
                long waitNanos = (long) ((Math.min(remaining, rateBps) - tokens) * 1_000_000_000d / rateBps);
                long ms = Math.max(1, Math.min(200, waitNanos / 1_000_000));
                wait(ms);
            }
        }
    }

    private void refill() {
        long now = System.nanoTime();
        tokens = Math.min(rateBps, tokens + (now - lastRefillNanos) * rateBps / 1_000_000_000d);
        lastRefillNanos = now;
    }
}
