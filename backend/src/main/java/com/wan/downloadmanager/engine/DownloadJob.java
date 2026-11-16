package com.wan.downloadmanager.engine;

import com.wan.downloadmanager.security.Redactor;
import com.wan.downloadmanager.security.SafeFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

/**
 * Downloads one resource into {@code <target>.wdmpart}, then moves it to a name that does not exist
 * yet. With byte-range support and a known length it uses several connections, each writing its own
 * range at its own file position. Progress is always real bytes received.
 *
 * <p>Resume rules: a stored plan is reused only if the server still supports ranges and the
 * length, ETag and Last-Modified are unchanged; every ranged request carries If-Range, so a resource
 * that changes mid-download is detected ({@link HttpFetcher.SourceChangedException}) and the download
 * restarts from zero (at most twice).
 */
public class DownloadJob implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(DownloadJob.class);
    private static final int BUFFER = 64 * 1024;
    private static final int MAX_SOURCE_RESTARTS = 2;

    public record Spec(String id, URI uri, Map<String, String> headers, Path saveDir, String fileName, Path tempPath,
                       int connections, int maxRetries, long retryBaseDelayMs,
                       Long knownTotal, String knownEtag, String knownLastModified, List<PartState> existingParts) {}

    private enum Stop { NONE, PAUSE, FAIL }

    private final Spec spec;
    private final HttpFetcher fetcher;
    private final JobStore store;
    private final List<BandwidthLimiter> limiters;
    private final LongConsumer globalBytes;
    private final SpeedMeter meter = new SpeedMeter();
    private final AtomicReference<Stop> stop = new AtomicReference<>(Stop.NONE);
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final Set<InputStream> openStreams = ConcurrentHashMap.newKeySet();
    private final Object sleeper = new Object();
    private volatile boolean sourceChanged;
    private volatile List<PartState> parts = List.of();
    private volatile Path tempPath;

    public DownloadJob(Spec spec, HttpFetcher fetcher, JobStore store, List<BandwidthLimiter> limiters, LongConsumer globalBytes) {
        this.spec = spec;
        this.fetcher = fetcher;
        this.store = store;
        this.limiters = limiters;
        this.globalBytes = globalBytes;
    }

    public String id() { return spec.id(); }
    public long speedBps() { return meter.bytesPerSecond(); }
    public List<PartState> parts() { return parts; }

    /** Stops all connections; state is kept for resume. Safe to call from any thread. */
    public void pause() {
        stop.compareAndSet(Stop.NONE, Stop.PAUSE);
        wakeAndClose();
    }

    private void fail(String reason) {
        failure.compareAndSet(null, reason);
        stop.compareAndSet(Stop.NONE, Stop.FAIL);
        wakeAndClose();
    }

    private void wakeAndClose() {
        for (InputStream in : openStreams) {
            try {
                in.close();
            } catch (IOException ignored) {
                // closing to unblock a read
            }
        }
        synchronized (sleeper) {
            sleeper.notifyAll();
        }
    }

    @Override
    public void run() {
        try {
            for (int restart = 0; ; restart++) {
                sourceChanged = false;
                runOnce(restart > 0);
                if (!sourceChanged || stop.get() != Stop.NONE) return;
                if (restart >= MAX_SOURCE_RESTARTS) {
                    fail("The file changed on the server repeatedly while downloading");
                    store.failed(failure.get(), parts, downloaded());
                    return;
                }
                log.info("Download {}: source changed on the server, restarting from zero", spec.id());
            }
        } catch (Throwable t) {
            log.warn("Download {} failed: {}", spec.id(), Redactor.redact(t.toString()));
            store.failed(describe(t), parts, downloaded());
        }
    }

    private void runOnce(boolean forceFresh) throws Exception {
        HttpFetcher.Probe probe = probeWithRetry();
        if (probe == null) return; // paused or failed during probe
        boolean resumed = !forceFresh && canResume(probe);
        tempPath = spec.tempPath() != null ? spec.tempPath() : spec.saveDir().resolve(SafeFiles.sanitize(spec.fileName()) + ".wdmpart");
        if (resumed) {
            parts = spec.existingParts();
        } else {
            List<PartState> fresh = new ArrayList<>();
            for (SegmentPlanner.Range r : SegmentPlanner.plan(probe.totalBytes(), spec.connections(), probe.rangeSupported())) {
                fresh.add(new PartState(r.index(), r.start(), r.end(), 0, PartState.Status.PENDING));
            }
            parts = fresh;
            Files.deleteIfExists(tempPath);
        }
        Files.createDirectories(tempPath.getParent());
        store.planned(probe, parts, resumed);

        String ifRange = probe.rangeSupported() ? probe.ifRangeValidator() : null;
        URI uri = probe.finalUri();
        try (FileChannel ch = FileChannel.open(tempPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            List<Thread> workers = new ArrayList<>();
            for (PartState p : parts) {
                if (p.complete()) {
                    p.status(PartState.Status.DONE);
                    continue;
                }
                workers.add(Thread.ofVirtual().name("wdm-" + spec.id() + "-" + p.index())
                        .start(() -> work(p, uri, ifRange, probe.rangeSupported(), ch)));
            }
            for (Thread w : workers) {
                while (w.isAlive()) {
                    w.join(500);
                    store.progress(parts, downloaded(), meter.bytesPerSecond());
                }
            }
            if (sourceChanged && stop.get() == Stop.NONE) return;
            switch (stop.get()) {
                case PAUSE -> {
                    store.paused(parts, downloaded());
                    return;
                }
                case FAIL -> {
                    store.failed(failure.get(), parts, downloaded());
                    return;
                }
                default -> { }
            }
            long size = downloaded();
            if (probe.totalBytes() != null && size != probe.totalBytes()) {
                store.failed("Size check failed: got " + size + " of " + probe.totalBytes() + " bytes", parts, size);
                return;
            }
            ch.truncate(size);
            ch.force(true);
        }
        Path target = SafeFiles.uniqueTarget(spec.saveDir(), spec.fileName() != null ? spec.fileName() : probe.fileName(), tempPath);
        String sha = sha256(tempPath);
        try {
            Files.move(tempPath, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempPath, target);
        }
        store.completed(target, Files.size(target), sha);
    }

    private HttpFetcher.Probe probeWithRetry() throws Exception {
        for (int attempt = 0; ; attempt++) {
            if (stop.get() != Stop.NONE) {
                if (stop.get() == Stop.PAUSE) store.paused(parts, downloaded());
                else store.failed(failure.get(), parts, downloaded());
                return null;
            }
            try {
                return fetcher.probe(spec.uri(), spec.headers());
            } catch (HttpFetcher.HttpStatusException e) {
                if (!e.retryable() || attempt >= spec.maxRetries()) throw e;
                store.retrying(attempt + 1, e.getMessage());
            } catch (IOException e) {
                if (attempt >= spec.maxRetries()) throw e;
                store.retrying(attempt + 1, e.toString());
            }
            backoff(attempt);
        }
    }

    private boolean canResume(HttpFetcher.Probe p) {
        List<PartState> existing = spec.existingParts();
        if (existing == null || existing.isEmpty() || existing.stream().allMatch(x -> x.downloaded() == 0)) return false;
        if (!p.rangeSupported() || p.totalBytes() == null) return false;
        if (!Objects.equals(p.totalBytes(), spec.knownTotal())) return false;
        if (spec.knownEtag() != null && !spec.knownEtag().equals(p.etag())) return false;
        if (spec.knownLastModified() != null && !spec.knownLastModified().equals(p.lastModified())) return false;
        Path t = spec.tempPath();
        return t != null && Files.exists(t);
    }

    private void work(PartState p, URI uri, String ifRange, boolean rangeSupported, FileChannel ch) {
        p.status(PartState.Status.ACTIVE);
        int attempt = 0;
        while (stop.get() == Stop.NONE && !sourceChanged) {
            long pos = p.start() + p.downloaded();
            if (p.end() != null && pos > p.end()) {
                p.status(PartState.Status.DONE);
                return;
            }
            boolean restartable = rangeSupported && p.end() != null;
            if (!restartable && p.downloaded() > 0) {
                // No ranges: a broken stream can only start over.
                p.reset();
                p.status(PartState.Status.ACTIVE);
                pos = p.start();
            }
            Long end = rangeSupported ? p.end() : null;
            long reqStart = rangeSupported ? pos : 0;
            try {
                HttpResponse<InputStream> res = fetcher.open(uri, spec.headers(), reqStart, end, ifRange);
                InputStream in = res.body();
                openStreams.add(in);
                try (in) {
                    byte[] buf = new byte[BUFFER];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (stop.get() != Stop.NONE || sourceChanged) return;
                        if (n == 0) continue;
                        if (p.end() != null) n = (int) Math.min(n, p.end() - pos + 1);
                        if (n <= 0) break;
                        for (BandwidthLimiter l : limiters) l.acquire(n);
                        ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
                        long at = pos;
                        while (bb.hasRemaining()) at += ch.write(bb, at);
                        pos += n;
                        p.add(n);
                        meter.add(n);
                        globalBytes.accept(n);
                        attempt = 0;
                    }
                } finally {
                    openStreams.remove(in);
                }
                if (stop.get() != Stop.NONE) return;
                if (p.end() != null && pos <= p.end()) throw new IOException("Connection closed before the range was complete");
                p.status(PartState.Status.DONE);
                return;
            } catch (HttpFetcher.SourceChangedException e) {
                sourceChanged = true;
                wakeAndClose();
                return;
            } catch (HttpFetcher.HttpStatusException e) {
                if (!e.retryable()) {
                    p.status(PartState.Status.FAILED);
                    fail("Server answered " + e.getMessage());
                    return;
                }
                attempt = retryOrFail(p, attempt, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                if (stop.get() != Stop.NONE) return;
                attempt = retryOrFail(p, attempt, describe(e));
            } catch (RuntimeException e) {
                p.status(PartState.Status.FAILED);
                fail(describe(e));
                return;
            }
        }
    }

    private int retryOrFail(PartState p, int attempt, String reason) {
        int next = attempt + 1;
        if (next > spec.maxRetries()) {
            p.status(PartState.Status.FAILED);
            fail("Gave up after " + spec.maxRetries() + " retries: " + reason);
            return next;
        }
        store.retrying(next, reason);
        backoff(attempt);
        return next;
    }

    private void backoff(int attempt) {
        long delay = Math.min(spec.retryBaseDelayMs() * (1L << Math.min(attempt, 6)), 30_000);
        synchronized (sleeper) {
            try {
                if (stop.get() == Stop.NONE) sleeper.wait(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private long downloaded() {
        long sum = 0;
        for (PartState p : parts) sum += p.downloaded();
        return sum;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return Redactor.redact(msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg);
    }
}
