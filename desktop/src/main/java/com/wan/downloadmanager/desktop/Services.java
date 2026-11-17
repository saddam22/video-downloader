package com.wan.downloadmanager.desktop;

import com.wan.downloadmanager.browser.BrowserService;
import com.wan.downloadmanager.download.BatchService;
import com.wan.downloadmanager.download.DownloadService;
import com.wan.downloadmanager.engine.EngineService;
import com.wan.downloadmanager.entitlement.EntitlementService;
import com.wan.downloadmanager.history.HistoryService;
import com.wan.downloadmanager.identity.IdentityService;
import com.wan.downloadmanager.media.ConversionService;
import com.wan.downloadmanager.media.FfmpegService;
import com.wan.downloadmanager.media.MediaService;
import com.wan.downloadmanager.notification.EventBus;
import com.wan.downloadmanager.notification.EventLog;
import com.wan.downloadmanager.queue.QueueService;
import com.wan.downloadmanager.repository.DownloadCategoryRepository;
import com.wan.downloadmanager.scheduler.SchedulerService;
import com.wan.downloadmanager.security.LocalApiAuth;
import com.wan.downloadmanager.stats.StatsService;
import com.wan.downloadmanager.storage.SettingsService;
import org.springframework.context.ConfigurableApplicationContext;

/** The embedded backend's services, used in-process by the UI (no HTTP hop for the desktop itself). */
public record Services(ConfigurableApplicationContext ctx, DownloadService downloads, BatchService batch, QueueService queues,
                       SchedulerService scheduler, HistoryService history, StatsService stats, MediaService media,
                       ConversionService conversions, FfmpegService ffmpeg, BrowserService browsers, IdentityService identity,
                       EntitlementService entitlements, SettingsService settings, EventBus bus, EventLog events,
                       EngineService engine, DownloadCategoryRepository categories, LocalApiAuth api) {

    public static Services from(ConfigurableApplicationContext c) {
        return new Services(c, c.getBean(DownloadService.class), c.getBean(BatchService.class), c.getBean(QueueService.class),
                c.getBean(SchedulerService.class), c.getBean(HistoryService.class), c.getBean(StatsService.class),
                c.getBean(MediaService.class), c.getBean(ConversionService.class), c.getBean(FfmpegService.class),
                c.getBean(BrowserService.class), c.getBean(IdentityService.class), c.getBean(EntitlementService.class),
                c.getBean(SettingsService.class), c.getBean(EventBus.class), c.getBean(EventLog.class),
                c.getBean(EngineService.class), c.getBean(DownloadCategoryRepository.class), c.getBean(LocalApiAuth.class));
    }

    public String dataDir() {
        return ctx.getEnvironment().getProperty("wan.data-dir");
    }
}
