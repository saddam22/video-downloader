package com.wan.downloadmanager.engine;

import java.util.concurrent.atomic.AtomicLong;

/** Live state of one byte range; shared between a worker and the progress reporter. */
public final class PartState {
    public enum Status { PENDING, ACTIVE, DONE, FAILED }

    private final int index;
    private final long start;
    private final Long end;
    private final AtomicLong downloaded;
    private volatile Status status;

    public PartState(int index, long start, Long end, long downloaded, Status status) {
        this.index = index;
        this.start = start;
        this.end = end;
        this.downloaded = new AtomicLong(downloaded);
        this.status = status;
    }

    public int index() { return index; }
    public long start() { return start; }
    /** Inclusive end, or null when the stream length is unknown. */
    public Long end() { return end; }
    public long downloaded() { return downloaded.get(); }
    public Status status() { return status; }

    void add(long n) { downloaded.addAndGet(n); }
    void reset() { downloaded.set(0); status = Status.PENDING; }
    void status(Status s) { status = s; }

    public long length() {
        return end == null ? -1 : end - start + 1;
    }

    public boolean complete() {
        return status == Status.DONE || (end != null && downloaded.get() >= length());
    }
}
