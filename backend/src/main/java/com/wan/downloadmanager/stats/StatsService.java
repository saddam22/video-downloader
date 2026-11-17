package com.wan.downloadmanager.stats;

import com.wan.downloadmanager.engine.EngineService;
import com.wan.downloadmanager.entity.DownloadStatus;
import com.wan.downloadmanager.entity.SpeedStatistic;
import com.wan.downloadmanager.repository.DownloadRepository;
import com.wan.downloadmanager.repository.SpeedStatisticRepository;
import com.wan.downloadmanager.storage.SettingsService;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Dashboard figures — all measured, none estimated. */
@Service
public class StatsService {
    public record Point(long at, long bytes, long peakBps) {}

    public record Dashboard(long active, long queued, long completed, long failed, long totalDownloadedBytes,
                            long currentSpeedBps, long averageSpeedBps, List<Point> activity,
                            String downloadDir, Long diskFreeBytes, Long diskTotalBytes) {}

    private final DownloadRepository downloads;
    private final SpeedStatisticRepository stats;
    private final EngineService engine;
    private final SettingsService settings;

    public StatsService(DownloadRepository downloads, SpeedStatisticRepository stats, EngineService engine, SettingsService settings) {
        this.downloads = downloads;
        this.stats = stats;
        this.engine = engine;
        this.settings = settings;
    }

    public Dashboard dashboard() {
        long since = System.currentTimeMillis() - 24 * 3600_000L;
        List<SpeedStatistic> rows = stats.findByBucketAtGreaterThanEqualOrderByBucketAt(since);
        List<Point> points = rows.stream().map(s -> new Point(s.getBucketAt(), s.getBytes(), s.getPeakBps())).toList();
        long bytes24 = rows.stream().mapToLong(SpeedStatistic::getBytes).sum();
        long avg = rows.isEmpty() ? 0 : bytes24 / (rows.size() * 60L);
        Path dir = Path.of(settings.get().downloadDir());
        File probe = dir.toFile();
        while (probe != null && !probe.exists()) probe = probe.getParentFile();
        Long free = probe != null ? probe.getUsableSpace() : null;
        Long total = probe != null ? probe.getTotalSpace() : null;
        return new Dashboard(downloads.countByStatus(DownloadStatus.DOWNLOADING), downloads.countByStatus(DownloadStatus.QUEUED),
                downloads.countByStatus(DownloadStatus.COMPLETED), downloads.countByStatus(DownloadStatus.FAILED),
                stats.totalBytes(), engine.currentSpeedBps(), avg, points, dir.toString(), free, total);
    }

    static boolean exists(Path p) {
        return Files.exists(p);
    }
}
