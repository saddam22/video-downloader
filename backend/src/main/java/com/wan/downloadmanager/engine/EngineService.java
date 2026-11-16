package com.wan.downloadmanager.engine;

import com.wan.downloadmanager.entity.SpeedStatistic;
import com.wan.downloadmanager.notification.EventBus;
import com.wan.downloadmanager.repository.SpeedStatisticRepository;
import com.wan.downloadmanager.security.UrlPolicy;
import com.wan.downloadmanager.storage.SettingsService;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Runs {@link DownloadJob}s on virtual threads; owns the global bandwidth limiter and speed statistics. */
@Service
public class EngineService {
    private final HttpFetcher fetcher;
    private final BandwidthLimiter global;
    private final SpeedMeter globalMeter = new SpeedMeter();
    private final AtomicLong minuteBytes = new AtomicLong();
    private volatile long minutePeak;
    private final Map<String, DownloadJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("wdm-job-", 0).factory());
    private final SpeedStatisticRepository stats;
    private final EventBus bus;

    public EngineService(UrlPolicy policy, SettingsService settings, SpeedStatisticRepository stats, EventBus bus) {
        this.fetcher = new HttpFetcher(policy);
        this.global = new BandwidthLimiter(settings.get().globalSpeedLimitBps());
        this.stats = stats;
        this.bus = bus;
        settings.onChange(s -> global.setRate(s.globalSpeedLimitBps()));
    }

    public HttpFetcher fetcher() {
        return fetcher;
    }

    /** Starts a job; {@code onExit} runs after the job has reported its final state. */
    public void start(DownloadJob.Spec spec, JobStore store, BandwidthLimiter perDownload, Runnable onExit) {
        List<BandwidthLimiter> limiters = perDownload == null ? List.of(global) : List.of(global, perDownload);
        DownloadJob job = new DownloadJob(spec, fetcher, store, limiters, n -> {
            globalMeter.add(n);
            minuteBytes.addAndGet(n);
        });
        if (jobs.putIfAbsent(spec.id(), job) != null) return;
        executor.submit(() -> {
            try {
                job.run();
            } finally {
                jobs.remove(spec.id(), job);
                onExit.run();
            }
        });
    }

    public boolean isRunning(String id) {
        return jobs.containsKey(id);
    }

    public Optional<DownloadJob> job(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public void pause(String id) {
        DownloadJob j = jobs.get(id);
        if (j != null) j.pause();
    }

    /** Waits until a paused job has fully stopped (bounded). */
    public void awaitStopped(String id, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (jobs.containsKey(id) && System.currentTimeMillis() < deadline) Thread.sleep(20);
    }

    public int activeCount() {
        return jobs.size();
    }

    public long currentSpeedBps() {
        return globalMeter.bytesPerSecond();
    }

    public long globalLimitBps() {
        return global.rate();
    }

    public void setGlobalLimit(long bps) {
        global.setRate(bps);
    }

    @Scheduled(fixedRate = 1000)
    void publishSpeed() {
        long bps = globalMeter.bytesPerSecond();
        minutePeak = Math.max(minutePeak, bps);
        bus.publish(EventBus.SPEED, Map.of("bps", bps, "active", jobs.size(), "at", System.currentTimeMillis()));
    }

    /** Persists a per-minute throughput bucket (only minutes with traffic). */
    @Scheduled(cron = "0 * * * * *")
    void flushMinute() {
        long bytes = minuteBytes.getAndSet(0);
        long peak = minutePeak;
        minutePeak = 0;
        if (bytes == 0) return;
        long bucket = (System.currentTimeMillis() / 60_000 - 1) * 60_000;
        SpeedStatistic s = stats.findByBucketAt(bucket).orElseGet(() -> {
            SpeedStatistic n = new SpeedStatistic();
            n.setId(UUID.randomUUID().toString());
            n.setBucketAt(bucket);
            return n;
        });
        s.setBytes(s.getBytes() + bytes);
        s.setPeakBps(Math.max(s.getPeakBps(), peak));
        stats.save(s);
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        jobs.values().forEach(DownloadJob::pause);
        long deadline = System.currentTimeMillis() + 5000;
        while (!jobs.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        executor.shutdown();
    }
}
