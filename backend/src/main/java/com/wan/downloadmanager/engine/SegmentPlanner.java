package com.wan.downloadmanager.engine;

import java.util.ArrayList;
import java.util.List;

/** Splits a known length into contiguous inclusive byte ranges. */
public final class SegmentPlanner {
    public static final long MIN_SEGMENT = 512 * 1024;

    private SegmentPlanner() {}

    public record Range(int index, long start, Long end) {}

    /**
     * @param total       resource length, or null when unknown
     * @param connections requested connections (1–16)
     * @param ranges      whether the server honours byte ranges
     */
    public static List<Range> plan(Long total, int connections, boolean ranges) {
        if (total == null || !ranges || connections <= 1 || total < 2 * MIN_SEGMENT) {
            return List.of(new Range(0, 0, total == null || total == 0 ? null : total - 1));
        }
        int n = (int) Math.min(Math.min(connections, 16), total / MIN_SEGMENT);
        long size = total / n;
        List<Range> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long start = i * size;
            long end = i == n - 1 ? total - 1 : start + size - 1;
            out.add(new Range(i, start, end));
        }
        return out;
    }
}
