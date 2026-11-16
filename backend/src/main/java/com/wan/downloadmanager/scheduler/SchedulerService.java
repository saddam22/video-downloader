package com.wan.downloadmanager.scheduler;

import com.wan.downloadmanager.engine.EngineService;
import com.wan.downloadmanager.entitlement.EntitlementService;
import com.wan.downloadmanager.entitlement.Feature;
import com.wan.downloadmanager.entity.Schedule;
import com.wan.downloadmanager.exception.WdmException;
import com.wan.downloadmanager.notification.EventLog;
import com.wan.downloadmanager.queue.QueueService;
import com.wan.downloadmanager.repository.DownloadQueueRepository;
import com.wan.downloadmanager.repository.ScheduleRepository;
import com.wan.downloadmanager.storage.SettingsService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Download windows: at {@code startTime} on the selected days a queue starts (optionally with a
 * bandwidth cap); at {@code endTime} it stops again. A one-shot schedule runs on one date only.
 */
@Service
public class SchedulerService {
    public record ScheduleRequest(@NotBlank @Size(max = 80) String name, @NotBlank String queueId,
                                  @NotBlank @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d") String startTime,
                                  @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d") String endTime,
                                  List<String> daysOfWeek,
                                  @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String oneShotDate,
                                  Long speedLimitBps, Boolean enabled) {}

    private final ScheduleRepository schedules;
    private final DownloadQueueRepository queues;
    private final QueueService queueService;
    private final EngineService engine;
    private final SettingsService settings;
    private final EntitlementService entitlements;
    private final EventLog events;
    private final Set<String> openWindows = Collections.synchronizedSet(new HashSet<>());
    private Clock clock = Clock.systemDefaultZone();

    public SchedulerService(ScheduleRepository schedules, DownloadQueueRepository queues, QueueService queueService,
                            EngineService engine, SettingsService settings, EntitlementService entitlements, EventLog events) {
        this.schedules = schedules;
        this.queues = queues;
        this.queueService = queueService;
        this.engine = engine;
        this.settings = settings;
        this.entitlements = entitlements;
        this.events = events;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    public List<Schedule> list() {
        return schedules.findAll();
    }

    public Schedule save(String id, ScheduleRequest r) {
        entitlements.require(Feature.DOWNLOAD_SCHEDULER);
        queues.findById(r.queueId()).orElseThrow(() -> WdmException.notFound("Queue"));
        Schedule s = id == null ? new Schedule() : schedules.findById(id).orElseThrow(() -> WdmException.notFound("Schedule"));
        if (id == null) {
            s.setId(UUID.randomUUID().toString());
            s.setCreatedAt(System.currentTimeMillis());
        }
        s.setName(r.name().trim());
        s.setQueueId(r.queueId());
        s.setStartTime(r.startTime());
        s.setEndTime(r.endTime() == null || r.endTime().isBlank() ? null : r.endTime());
        List<String> days = r.daysOfWeek() == null || r.daysOfWeek().isEmpty()
                ? List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN") : r.daysOfWeek();
        days.forEach(SchedulerService::day);
        s.setDaysOfWeek(String.join(",", days));
        s.setOneShotDate(r.oneShotDate());
        s.setSpeedLimitBps(r.speedLimitBps() == null ? 0 : Math.max(0, r.speedLimitBps()));
        s.setEnabled(r.enabled() == null || r.enabled());
        return schedules.save(s);
    }

    public void delete(String id) {
        schedules.deleteById(id);
        openWindows.remove(id);
    }

    @Scheduled(fixedDelay = 20_000, initialDelay = 5_000)
    public void tick() {
        if (!entitlements.has(Feature.DOWNLOAD_SCHEDULER)) return;
        LocalDateTime now = LocalDateTime.now(clock);
        for (Schedule s : schedules.findByEnabledTrue()) {
            boolean inWindow = inWindow(s, now);
            boolean open = openWindows.contains(s.getId());
            if (inWindow && !open && !ranToday(s, now)) {
                openWindows.add(s.getId());
                s.setLastRunAt(System.currentTimeMillis());
                if (s.getOneShotDate() != null && s.getEndTime() == null) s.setEnabled(false);
                schedules.save(s);
                if (s.getSpeedLimitBps() > 0) engine.setGlobalLimit(s.getSpeedLimitBps());
                queueService.start(s.getQueueId());
                events.info("scheduler", "Schedule '" + s.getName() + "' started its queue", s.getId());
            } else if (!inWindow && open) {
                openWindows.remove(s.getId());
                if (s.getEndTime() != null) {
                    queueService.stop(s.getQueueId());
                    if (s.getOneShotDate() != null) {
                        s.setEnabled(false);
                        schedules.save(s);
                    }
                    events.info("scheduler", "Schedule '" + s.getName() + "' window ended; queue stopped", s.getId());
                }
                if (s.getSpeedLimitBps() > 0) engine.setGlobalLimit(settings.get().globalSpeedLimitBps());
            }
        }
    }

    static boolean inWindow(Schedule s, LocalDateTime now) {
        if (s.getOneShotDate() != null && !LocalDate.parse(s.getOneShotDate()).equals(now.toLocalDate())) return false;
        Set<DayOfWeek> days = new HashSet<>();
        for (String d : s.getDaysOfWeek().split(",")) days.add(day(d.trim()));
        LocalTime t = now.toLocalTime();
        LocalTime start = LocalTime.parse(s.getStartTime());
        if (s.getEndTime() == null) {
            // Start-only schedule: fires once in the minutes after the start time.
            return days.contains(now.getDayOfWeek()) && !t.isBefore(start) && t.isBefore(start.plusMinutes(5));
        }
        LocalTime end = LocalTime.parse(s.getEndTime());
        if (start.isBefore(end)) return days.contains(now.getDayOfWeek()) && !t.isBefore(start) && t.isBefore(end);
        // Overnight window, e.g. 23:00–06:00: the day is the one on which it started.
        if (!t.isBefore(start)) return days.contains(now.getDayOfWeek());
        return t.isBefore(end) && days.contains(now.getDayOfWeek().minus(1));
    }

    private boolean ranToday(Schedule s, LocalDateTime now) {
        if (s.getLastRunAt() == null) return false;
        LocalDateTime last = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(s.getLastRunAt()), ZoneId.systemDefault());
        return java.time.Duration.between(last, now).toHours() < 12 && s.getEndTime() == null;
    }

    static DayOfWeek day(String d) {
        return switch (d.toUpperCase(Locale.ROOT)) {
            case "MON" -> DayOfWeek.MONDAY;
            case "TUE" -> DayOfWeek.TUESDAY;
            case "WED" -> DayOfWeek.WEDNESDAY;
            case "THU" -> DayOfWeek.THURSDAY;
            case "FRI" -> DayOfWeek.FRIDAY;
            case "SAT" -> DayOfWeek.SATURDAY;
            case "SUN" -> DayOfWeek.SUNDAY;
            default -> throw WdmException.badRequest("day_invalid", "Unknown day " + d);
        };
    }
}
