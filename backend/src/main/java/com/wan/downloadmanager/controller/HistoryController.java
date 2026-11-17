package com.wan.downloadmanager.controller;

import com.wan.downloadmanager.entity.AppEvent;
import com.wan.downloadmanager.entity.HistoryEntry;
import com.wan.downloadmanager.history.HistoryService;
import com.wan.downloadmanager.notification.EventLog;
import com.wan.downloadmanager.stats.StatsService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class HistoryController {
    private final HistoryService history;
    private final StatsService stats;
    private final EventLog events;

    public HistoryController(HistoryService history, StatsService stats, EventLog events) {
        this.history = history;
        this.stats = stats;
        this.events = events;
    }

    @GetMapping("/history")
    public List<HistoryEntry> history(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "500") int limit) {
        return history.search(q, status, limit);
    }

    @GetMapping(value = "/history/export", produces = "text/csv")
    public ResponseEntity<String> export(@RequestParam(required = false) String q, @RequestParam(required = false) String status) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"wan-download-history.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(history.exportCsv(q, status));
    }

    @DeleteMapping("/history")
    public ResponseEntity<Void> clear() {
        history.clear();
        events.audit("history", "History cleared", null);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/stats")
    public StatsService.Dashboard dashboard() {
        return stats.dashboard();
    }

    @GetMapping("/events")
    public List<AppEvent> events() {
        return events.recent();
    }
}
