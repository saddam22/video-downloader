package com.wan.downloadmanager.engine;

import java.nio.file.Path;
import java.util.List;

/** Persistence callbacks of a {@link DownloadJob}. Implementations must be thread-safe and must not throw. */
public interface JobStore {
    /** Probe done and parts planned (fresh or resumed). */
    void planned(HttpFetcher.Probe probe, List<PartState> parts, boolean resumed);

    /** Periodic progress (about twice a second while running). */
    void progress(List<PartState> parts, long downloaded, long speedBps);

    void retrying(int attempt, String reason);

    void paused(List<PartState> parts, long downloaded);

    void failed(String reason, List<PartState> parts, long downloaded);

    void completed(Path file, long size, String sha256);
}
