package com.wan.downloadmanager.download;

import com.wan.downloadmanager.dto.CreateDownloadRequest;
import com.wan.downloadmanager.dto.DownloadView;
import com.wan.downloadmanager.engine.BandwidthLimiter;
import com.wan.downloadmanager.engine.DownloadJob;
import com.wan.downloadmanager.engine.EngineService;
import com.wan.downloadmanager.engine.HttpFetcher;
import com.wan.downloadmanager.engine.JobStore;
import com.wan.downloadmanager.engine.PartState;
import com.wan.downloadmanager.entitlement.EntitlementService;
import com.wan.downloadmanager.entitlement.Feature;
import com.wan.downloadmanager.entity.*;
import com.wan.downloadmanager.exception.WdmException;
import com.wan.downloadmanager.notification.EventBus;
import com.wan.downloadmanager.notification.EventLog;
import com.wan.downloadmanager.repository.*;
import com.wan.downloadmanager.security.SafeFiles;
import com.wan.downloadmanager.security.UrlPolicy;
import com.wan.downloadmanager.storage.Settings;
import com.wan.downloadmanager.storage.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Download lifecycle: create → QUEUED → DOWNLOADING → COMPLETED / FAILED / PAUSED / CANCELLED.
 * The database row (with its parts) is the recovery source of truth; live speed is kept in memory.
 */
@Service
public class DownloadService {
    private static final Logger log = LoggerFactory.getLogger(DownloadService.class);
    public static final String MAIN_QUEUE = "queue-main";
    private static final Set<String> ALLOWED_HEADERS = Set.of("referer", "accept-language", "accept");
    private static final EnumSet<DownloadStatus> ACTIVE_OR_WAITING = EnumSet.of(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED);

    private final DownloadRepository downloads;
    private final DownloadPartRepository parts;
    private final DownloadHeaderRepository headers;
    private final QueueItemRepository queueItems;
    private final DownloadQueueRepository queues;
    private final HistoryEntryRepository history;
    private final EngineService engine;
    private final UrlPolicy urlPolicy;
    private final SettingsService settings;
    private final EntitlementService entitlements;
    private final Categorizer categorizer;
    private final EventBus bus;
    private final EventLog events;
    private final Map<String, Live> live = new ConcurrentHashMap<>();
    private final Object pumpLock = new Object();
    /** Ids whose job should end CANCELLED instead of PAUSED. */
    private final Set<String> cancelling = ConcurrentHashMap.newKeySet();
    /** Ids whose job is being stopped to go back to QUEUED (queue stopped / schedule window ended). */
    private final Set<String> requeueing = ConcurrentHashMap.newKeySet();

    private record Live(long downloaded, long speedBps, int activeConnections, long lastPersist) {}

    /**
     * Runs downloads that are not a single HTTP resource (HLS streams). It must report through
     * {@link #externalProgress}, {@link #externalCompleted}, {@link #externalFailed} and {@link #externalStopped}.
     */
    public interface ExternalRunner {
        boolean handles(Download d);
        void start(Download d);
        void stop(String id);
        boolean isRunning(String id);
        int activeCount();
    }

    private volatile ExternalRunner external;

    public void setExternalRunner(ExternalRunner runner) {
        this.external = runner;
    }

    private boolean running(String id) {
        return engine.isRunning(id) || (external != null && external.isRunning(id));
    }

    private void stopJob(String id) {
        if (engine.isRunning(id)) engine.pause(id);
        else if (external != null) external.stop(id);
    }

    private void awaitStopped(String id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (running(id) && System.currentTimeMillis() < deadline) Thread.sleep(20);
    }

    /** Creates a MEDIA download row handled by the external runner (HLS). */
    public DownloadView createExternal(String url, String pageUrl, String fileName, String mimeType, Map<String, String> internalHeaders) {
        entitlements.require(Feature.DOWNLOAD_BASIC);
        URI uri = urlPolicy.validate(url);
        Settings s = settings.get();
        DownloadCategory cat = categorizer.categorize(fileName, "video/mp4");
        Path dir = Path.of(s.downloadDir());
        if (s.autoCategorize() && cat != null) dir = SafeFiles.inside(dir.toAbsolutePath().normalize(), cat.getSubfolder());
        SafeFiles.ensureDirectory(dir);
        Download d = new Download();
        d.setId(UUID.randomUUID().toString());
        d.setUrl(uri.toString());
        d.setPageUrl(pageUrl != null ? urlPolicy.parse(pageUrl).toString() : null);
        d.setFileName(SafeFiles.sanitize(fileName));
        d.setNameLocked(true);
        d.setSaveDir(dir.toAbsolutePath().normalize().toString());
        d.setTempPath(SafeFiles.inside(dir.toAbsolutePath().normalize(), d.getId() + ".wdmpart").toString());
        d.setCategoryId(cat != null ? cat.getId() : null);
        d.setStatus(DownloadStatus.QUEUED);
        d.setConnections(1);
        d.setMaxRetries(s.retryCount());
        d.setMimeType(mimeType);
        d.setSource(DownloadSource.MEDIA);
        d.setCreatedAt(now());
        d.setUpdatedAt(now());
        downloads.save(d);
        internalHeaders.forEach((k, v) -> {
            DownloadHeader h = new DownloadHeader();
            h.setId(UUID.randomUUID().toString());
            h.setDownloadId(d.getId());
            h.setName(k);
            h.setValue(v);
            headers.save(h);
        });
        QueueItem qi = new QueueItem();
        qi.setId(UUID.randomUUID().toString());
        qi.setQueueId(MAIN_QUEUE);
        qi.setDownloadId(d.getId());
        qi.setPosition(queueItems.maxPosition(MAIN_QUEUE) + 1);
        queueItems.save(qi);
        events.info("download", "Added stream " + d.getFileName(), d.getId());
        publish(d);
        pump();
        return view(d);
    }

    public void externalProgress(String id, long downloaded, Long total, long speed) {
        live.put(id, new Live(downloaded, speed, 1, now()));
        downloads.findById(id).ifPresent(d -> {
            if (total != null) d.setTotalBytes(total);
            publish(d);
        });
    }

    public void externalCompleted(String id, Path file, long size, String sha256) {
        new Store(id).completed(file, size, sha256);
    }

    public void externalFailed(String id, String reason) {
        new Store(id).failed(reason, List.of(), live.containsKey(id) ? live.get(id).downloaded() : 0);
    }

    public void externalStopped(String id) {
        new Store(id).paused(List.of(), 0);
    }

    public DownloadService(DownloadRepository downloads, DownloadPartRepository parts, DownloadHeaderRepository headers,
                           QueueItemRepository queueItems, DownloadQueueRepository queues, HistoryEntryRepository history,
                           EngineService engine, UrlPolicy urlPolicy, SettingsService settings,
                           EntitlementService entitlements, Categorizer categorizer, EventBus bus, EventLog events) {
        this.downloads = downloads;
        this.parts = parts;
        this.headers = headers;
        this.queueItems = queueItems;
        this.queues = queues;
        this.history = history;
        this.engine = engine;
        this.urlPolicy = urlPolicy;
        this.settings = settings;
        this.entitlements = entitlements;
        this.categorizer = categorizer;
        this.bus = bus;
        this.events = events;
    }

    /** Crash recovery: anything that was DOWNLOADING when the app stopped goes back to the queue. */
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        for (Download d : downloads.findByStatusIn(List.of(DownloadStatus.DOWNLOADING))) {
            d.setStatus(DownloadStatus.QUEUED);
            d.setUpdatedAt(now());
            downloads.save(d);
            events.info("recovery", "Re-queued interrupted download " + d.getFileName(), d.getId());
        }
        pump();
    }

    // ---------------------------------------------------------------- create

    public DownloadView create(CreateDownloadRequest req) {
        entitlements.require(Feature.DOWNLOAD_BASIC);
        URI uri = urlPolicy.validate(req.url());
        Settings s = settings.get();
        boolean allowDup = Boolean.TRUE.equals(req.allowDuplicate());
        if (s.duplicateCheck() && !allowDup) {
            for (Download existing : downloads.findByUrl(uri.toString())) {
                if (existing.getStatus() != DownloadStatus.CANCELLED) {
                    throw new DuplicateDownloadException(existing.getId(), existing.getFileName(), existing.getStatus().name());
                }
            }
        }
        String queueId = req.queueId() == null || req.queueId().isBlank() ? MAIN_QUEUE : req.queueId();
        queues.findById(queueId).orElseThrow(() -> WdmException.notFound("Queue"));

        boolean named = req.fileName() != null && !req.fileName().isBlank();
        String name = named ? SafeFiles.sanitize(req.fileName()) : SafeFiles.fileNameFrom(null, uri);
        DownloadCategory cat = categorizer.categorize(name, null);
        Path dir = Path.of(req.saveDir() != null && !req.saveDir().isBlank() ? req.saveDir() : s.downloadDir());
        if (s.autoCategorize() && (req.saveDir() == null || req.saveDir().isBlank()) && cat != null) {
            dir = SafeFiles.inside(dir.toAbsolutePath().normalize(), cat.getSubfolder());
        }
        SafeFiles.ensureDirectory(dir);
        Map<String, String> hdrs = filterHeaders(req.headers(), req.pageUrl());

        Download d = new Download();
        d.setId(UUID.randomUUID().toString());
        d.setUrl(uri.toString());
        d.setPageUrl(req.pageUrl() != null ? urlPolicy.parse(req.pageUrl()).toString() : null);
        d.setFileName(name);
        d.setNameLocked(named);
        d.setSaveDir(dir.toAbsolutePath().normalize().toString());
        d.setTempPath(SafeFiles.inside(dir.toAbsolutePath().normalize(), d.getId() + ".wdmpart").toString());
        d.setCategoryId(cat != null ? cat.getId() : null);
        d.setStatus(Boolean.FALSE.equals(req.startNow()) ? DownloadStatus.PAUSED : DownloadStatus.QUEUED);
        int conns = req.connections() != null ? req.connections() : s.connections();
        d.setConnections(entitlements.has(Feature.DOWNLOAD_MULTITHREAD) ? entitlements.clampConnections(conns) : 1);
        d.setMaxRetries(s.retryCount());
        d.setSource(parseSource(req.source()));
        d.setCreatedAt(now());
        d.setUpdatedAt(now());
        downloads.save(d);
        hdrs.forEach((k, v) -> {
            DownloadHeader h = new DownloadHeader();
            h.setId(UUID.randomUUID().toString());
            h.setDownloadId(d.getId());
            h.setName(k);
            h.setValue(v);
            headers.save(h);
        });
        QueueItem qi = new QueueItem();
        qi.setId(UUID.randomUUID().toString());
        qi.setQueueId(queueId);
        qi.setDownloadId(d.getId());
        qi.setPosition(queueItems.maxPosition(queueId) + 1);
        queueItems.save(qi);
        events.info("download", "Added " + name + " from " + uri.getHost(), d.getId());
        publish(d);
        pump();
        return view(d);
    }

    private Map<String, String> filterHeaders(Map<String, String> in, String pageUrl) {
        Map<String, String> out = new LinkedHashMap<>();
        if (in != null) {
            for (var e : in.entrySet()) {
                String k = e.getKey() == null ? "" : e.getKey().trim();
                if (!ALLOWED_HEADERS.contains(k.toLowerCase(Locale.ROOT))) {
                    throw WdmException.badRequest("header_not_allowed", "Header not allowed: " + k);
                }
                String v = e.getValue() == null ? "" : e.getValue();
                if (v.length() > 2048 || v.contains("\r") || v.contains("\n")) throw WdmException.badRequest("header_invalid", "Invalid header value");
                out.put(k, v);
            }
        }
        if (pageUrl != null && !pageUrl.isBlank() && out.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("referer"))) {
            out.put("Referer", urlPolicy.parse(pageUrl).toString());
        }
        return out;
    }

    private static DownloadSource parseSource(String s) {
        if (s == null) return DownloadSource.MANUAL;
        try {
            return DownloadSource.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DownloadSource.MANUAL;
        }
    }

    // ---------------------------------------------------------------- actions

    public DownloadView pause(String id) {
        Download d = get(id);
        if (d.getStatus() == DownloadStatus.DOWNLOADING && running(id)) {
            stopJob(id); // the job reports PAUSED with its offsets
        } else if (d.getStatus() == DownloadStatus.QUEUED || d.getStatus() == DownloadStatus.DOWNLOADING) {
            setStatus(d, DownloadStatus.PAUSED, null);
        }
        return view(get(id));
    }

    public DownloadView resume(String id) {
        Download d = get(id);
        if (d.getStatus() == DownloadStatus.PAUSED) {
            setStatus(d, DownloadStatus.QUEUED, null);
            pump();
        }
        return view(get(id));
    }

    public DownloadView retry(String id) {
        Download d = get(id);
        if (d.getStatus() == DownloadStatus.FAILED || d.getStatus() == DownloadStatus.CANCELLED) {
            d.setRetryCount(0);
            setStatus(d, DownloadStatus.QUEUED, null);
            pump();
        }
        return view(get(id));
    }

    /** Stops and discards the partial file. The row stays (status CANCELLED) for history. */
    public DownloadView cancel(String id) throws InterruptedException {
        Download d = get(id);
        if (d.getStatus() == DownloadStatus.COMPLETED) throw WdmException.conflict("already_completed", "Download already completed");
        if (running(id)) {
            cancelling.add(id);
            stopJob(id);
            awaitStopped(id);
        }
        d = get(id);
        deleteTemp(d);
        parts.deleteByDownloadId(id);
        d.setDownloadedBytes(0);
        setStatus(d, DownloadStatus.CANCELLED, null);
        recordHistory(d, null);
        cancelling.remove(id);
        return view(get(id));
    }

    /** Removes the download (and optionally the finished file). Never touches anything outside its folder. */
    public void delete(String id, boolean deleteFile) throws InterruptedException {
        Download d = get(id);
        if (running(id)) {
            cancelling.add(id);
            stopJob(id);
            awaitStopped(id);
        }
        d = get(id);
        deleteTemp(d);
        if (deleteFile && d.getStatus() == DownloadStatus.COMPLETED) {
            try {
                Path dir = Path.of(d.getSaveDir()).toAbsolutePath().normalize();
                Files.deleteIfExists(SafeFiles.inside(dir, d.getFileName()));
            } catch (IOException e) {
                throw WdmException.conflict("delete_failed", "Could not delete file: " + e.getMessage());
            }
        }
        downloads.delete(d); // parts, headers and queue item cascade
        live.remove(id);
        cancelling.remove(id);
        events.audit("download", "Deleted " + d.getFileName() + (deleteFile ? " (file removed)" : ""), id);
        bus.publish(EventBus.DOWNLOADS, Map.of("deleted", id));
        pump();
    }

    public void pauseAll() {
        for (Download d : downloads.findByStatusIn(List.of(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING))) pause(d.getId());
    }

    public void resumeAll() {
        for (Download d : downloads.findByStatusIn(List.of(DownloadStatus.PAUSED))) {
            setStatus(d, DownloadStatus.QUEUED, null);
        }
        pump();
    }

    public int retryFailed() {
        List<Download> failed = downloads.findByStatusIn(List.of(DownloadStatus.FAILED));
        failed.forEach(d -> {
            d.setRetryCount(0);
            setStatus(d, DownloadStatus.QUEUED, null);
        });
        pump();
        return failed.size();
    }

    /** Stops a running download and puts it back in the queue (used when a queue is stopped). */
    public void requeue(String id) {
        if (running(id)) {
            requeueing.add(id);
            stopJob(id);
        }
    }

    // ---------------------------------------------------------------- scheduling

    /** Starts waiting downloads within the global, per-queue and plan concurrency limits. */
    public void pump() {
        synchronized (pumpLock) {
            int global = entitlements.clampConcurrent(settings.get().maxConcurrent());
            int running = engine.activeCount() + (external != null ? external.activeCount() : 0);
            if (running >= global) return;
            for (DownloadQueue q : queues.findAllByOrderByPriorityDescNameAsc()) {
                if (q.getState() != QueueState.RUNNING) continue;
                List<QueueItem> items = queueItems.findByQueueIdOrderByPosition(q.getId());
                int inQueue = (int) items.stream().filter(i -> running(i.getDownloadId())).count();
                for (QueueItem item : items) {
                    if (running >= global || inQueue >= q.getMaxConcurrent()) break;
                    Optional<Download> od = downloads.findById(item.getDownloadId());
                    if (od.isEmpty() || od.get().getStatus() != DownloadStatus.QUEUED || running(item.getDownloadId())) continue;
                    if (external != null && external.handles(od.get())) {
                        Download d = od.get();
                        d.setStatus(DownloadStatus.DOWNLOADING);
                        d.setErrorMessage(null);
                        if (d.getStartedAt() == null) d.setStartedAt(now());
                        downloads.save(d);
                        publish(d);
                        external.start(d);
                    } else {
                        startJob(od.get());
                    }
                    running++;
                    inQueue++;
                }
            }
        }
    }

    private void startJob(Download d) {
        List<PartState> existing = new ArrayList<>();
        for (DownloadPart p : parts.findByDownloadIdOrderByPartIndex(d.getId())) {
            existing.add(new PartState(p.getPartIndex(), p.getStartByte(), p.getEndByte(), p.getDownloadedBytes(), PartState.Status.PENDING));
        }
        Map<String, String> hdrs = new LinkedHashMap<>();
        headers.findByDownloadId(d.getId()).stream().filter(h -> !h.getName().toLowerCase(Locale.ROOT).startsWith("x-wdm-"))
                .forEach(h -> hdrs.put(h.getName(), h.getValue()));
        int conns = entitlements.has(Feature.DOWNLOAD_MULTITHREAD) ? entitlements.clampConnections(d.getConnections()) : 1;
        var spec = new DownloadJob.Spec(d.getId(), URI.create(d.getUrl()), hdrs, Path.of(d.getSaveDir()),
                d.isNameLocked() ? d.getFileName() : null, Path.of(d.getTempPath()), conns, d.getMaxRetries(), 1000,
                d.getTotalBytes(), d.getEtag(), d.getLastModified(), existing);
        d.setStatus(DownloadStatus.DOWNLOADING);
        d.setErrorMessage(null);
        if (d.getStartedAt() == null) d.setStartedAt(now());
        d.setUpdatedAt(now());
        downloads.save(d);
        publish(d);
        BandwidthLimiter perDownload = d.getSpeedLimitBps() > 0 ? new BandwidthLimiter(d.getSpeedLimitBps()) : null;
        engine.start(spec, new Store(d.getId()), perDownload, this::pump);
    }

    // ---------------------------------------------------------------- job callbacks

    private final class Store implements JobStore {
        private final String id;

        Store(String id) {
            this.id = id;
        }

        @Override
        public void planned(HttpFetcher.Probe p, List<PartState> ps, boolean resumed) {
            safely(() -> {
                Download d = get(id);
                d.setFinalUrl(p.finalUri().toString());
                d.setTotalBytes(p.totalBytes());
                d.setEtag(p.etag());
                d.setLastModified(p.lastModified());
                d.setResumable(p.rangeSupported());
                d.setMimeType(p.contentType());
                if (!d.isNameLocked() && p.fileName() != null) d.setFileName(p.fileName());
                if (d.getCategoryId() == null || Categorizer.OTHER.equals(d.getCategoryId())) {
                    DownloadCategory c = categorizer.categorize(d.getFileName(), p.contentType());
                    if (c != null) d.setCategoryId(c.getId());
                }
                d.setUpdatedAt(now());
                downloads.save(d);
                savePartsSnapshot(ps, !resumed);
                publish(d);
            });
        }

        @Override
        public void progress(List<PartState> ps, long downloaded, long speed) {
            int active = (int) ps.stream().filter(p -> p.status() == PartState.Status.ACTIVE).count();
            Live prev = live.get(id);
            long last = prev == null ? 0 : prev.lastPersist();
            if (now() - last > 2000) {
                safely(() -> {
                    savePartsSnapshot(ps, false);
                    Download d = get(id);
                    d.setDownloadedBytes(downloaded);
                    d.setUpdatedAt(now());
                    downloads.save(d);
                });
                last = now();
            }
            live.put(id, new Live(downloaded, speed, active, last));
            downloads.findById(id).ifPresent(DownloadService.this::publish);
        }

        @Override
        public void retrying(int attempt, String reason) {
            safely(() -> {
                Download d = get(id);
                d.setRetryCount(d.getRetryCount() + 1);
                downloads.save(d);
                events.warn("download", "Retry " + attempt + " for " + d.getFileName() + ": " + reason, id);
            });
        }

        @Override
        public void paused(List<PartState> ps, long downloaded) {
            safely(() -> {
                savePartsSnapshot(ps, false);
                Download d = get(id);
                d.setDownloadedBytes(downloaded);
                live.remove(id);
                if (cancelling.contains(id)) {
                    downloads.save(d);
                    return;
                }
                boolean back = requeueing.remove(id);
                setStatus(d, back ? DownloadStatus.QUEUED : DownloadStatus.PAUSED, null);
            });
        }

        @Override
        public void failed(String reason, List<PartState> ps, long downloaded) {
            safely(() -> {
                savePartsSnapshot(ps, false);
                Download d = get(id);
                d.setDownloadedBytes(downloaded);
                live.remove(id);
                setStatus(d, DownloadStatus.FAILED, reason);
                recordHistory(d, null);
                events.error("download", "Failed " + d.getFileName() + ": " + reason, id);
                bus.notify("error", "Download failed", d.getFileName() + ": " + reason, id);
            });
        }

        @Override
        public void completed(Path file, long size, String sha256) {
            safely(() -> {
                Download d = get(id);
                d.setFileName(file.getFileName().toString());
                d.setSaveDir(file.getParent().toString());
                d.setTotalBytes(size);
                d.setDownloadedBytes(size);
                d.setSha256(sha256);
                d.setCompletedAt(now());
                live.remove(id);
                parts.deleteByDownloadId(id);
                setStatus(d, DownloadStatus.COMPLETED, null);
                recordHistory(d, file);
                events.info("download", "Completed " + d.getFileName() + " (" + size + " bytes)", id);
                bus.notify("success", "Download complete", d.getFileName(), id);
            });
        }

        private void savePartsSnapshot(List<PartState> ps, boolean replace) {
            if (replace) parts.deleteByDownloadId(id);
            Map<Integer, DownloadPart> existing = new HashMap<>();
            if (!replace) parts.findByDownloadIdOrderByPartIndex(id).forEach(p -> existing.put(p.getPartIndex(), p));
            List<DownloadPart> rows = new ArrayList<>();
            for (PartState s : ps) {
                DownloadPart row = existing.get(s.index());
                if (row == null) {
                    row = new DownloadPart();
                    row.setId(UUID.randomUUID().toString());
                    row.setDownloadId(id);
                    row.setPartIndex(s.index());
                }
                row.setStartByte(s.start());
                row.setEndByte(s.end());
                row.setDownloadedBytes(s.downloaded());
                row.setStatus(PartStatus.valueOf(s.status().name()));
                rows.add(row);
            }
            parts.saveAll(rows);
        }

        private void safely(Runnable r) {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.warn("Could not persist state of download {}: {}", id, e.toString());
            }
        }
    }

    // ---------------------------------------------------------------- queries

    public List<DownloadView> list(String filter) {
        List<DownloadView> out = new ArrayList<>();
        for (Download d : downloads.findAllByOrderByCreatedAtDesc()) {
            boolean keep = switch (filter == null ? "all" : filter) {
                case "active" -> ACTIVE_OR_WAITING.contains(d.getStatus());
                case "completed" -> d.getStatus() == DownloadStatus.COMPLETED;
                case "failed" -> d.getStatus() == DownloadStatus.FAILED;
                default -> true;
            };
            if (keep) out.add(view(d));
        }
        return out;
    }

    public DownloadView find(String id) {
        return view(get(id));
    }

    public Download get(String id) {
        return downloads.findById(id).orElseThrow(() -> WdmException.notFound("Download"));
    }

    public DownloadView view(Download d) {
        Live l = live.get(d.getId());
        long done = l != null ? l.downloaded() : d.getDownloadedBytes();
        long speed = l != null ? l.speedBps() : 0;
        Long total = d.getTotalBytes();
        Double progress = null;
        if (total != null && total > 0) progress = Math.min(1.0, (double) done / total);
        else if (d.getStatus() == DownloadStatus.COMPLETED) progress = 1.0;
        Long eta = null;
        if (total != null && speed > 0) eta = Math.max(0, (total - done) / speed);
        String queueId = queueItems.findByDownloadId(d.getId()).map(QueueItem::getQueueId).orElse(null);
        String filePath = d.getStatus() == DownloadStatus.COMPLETED ? Path.of(d.getSaveDir(), d.getFileName()).toString() : null;
        return new DownloadView(d.getId(), d.getUrl(), d.getPageUrl(), d.getFileName(), d.getSaveDir(), filePath, d.getCategoryId(),
                queueId, d.getStatus().name(), total, done, progress, speed, eta, d.getConnections(),
                l != null ? l.activeConnections() : 0, d.isResumable(), d.getErrorMessage(), d.getSource().name(),
                d.getMimeType(), d.getSha256(), d.getCreatedAt(), d.getCompletedAt());
    }

    // ---------------------------------------------------------------- helpers

    private void setStatus(Download d, DownloadStatus status, String error) {
        d.setStatus(status);
        d.setErrorMessage(error);
        d.setUpdatedAt(now());
        downloads.save(d);
        publish(d);
    }

    private void publish(Download d) {
        bus.publish(EventBus.DOWNLOADS, view(d));
    }

    private void recordHistory(Download d, Path file) {
        HistoryEntry h = new HistoryEntry();
        h.setId(UUID.randomUUID().toString());
        h.setDownloadId(d.getId());
        h.setFileName(d.getFileName());
        h.setFileType(SafeFiles.extension(d.getFileName()));
        h.setUrl(d.getUrl());
        h.setSizeBytes(d.getTotalBytes());
        h.setStatus(d.getStatus().name());
        h.setSavePath(file != null ? file.toString() : null);
        h.setCompletedAt(now());
        history.save(h);
    }

    private void deleteTemp(Download d) {
        if (d.getTempPath() == null) return;
        try {
            Path temp = Path.of(d.getTempPath()).toAbsolutePath().normalize();
            if (temp.getFileName().toString().endsWith(".wdmpart")) Files.deleteIfExists(temp);
        } catch (IOException e) {
            log.warn("Could not delete partial file of {}: {}", d.getId(), e.toString());
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
