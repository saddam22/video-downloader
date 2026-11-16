package com.wan.downloadmanager.notification;

import com.wan.downloadmanager.entity.AppEvent;
import com.wan.downloadmanager.entity.EventLevel;
import com.wan.downloadmanager.repository.AppEventRepository;
import com.wan.downloadmanager.security.Redactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Structured application/audit events (SQLite + log), always redacted. Never fails the caller. */
@Service
public class EventLog {
    private static final Logger log = LoggerFactory.getLogger("wdm.events");
    private final AppEventRepository repo;

    public EventLog(AppEventRepository repo) {
        this.repo = repo;
    }

    public void info(String category, String message, String subjectId) { record(EventLevel.INFO, category, message, subjectId); }
    public void warn(String category, String message, String subjectId) { record(EventLevel.WARN, category, message, subjectId); }
    public void error(String category, String message, String subjectId) { record(EventLevel.ERROR, category, message, subjectId); }
    public void audit(String category, String message, String subjectId) { record(EventLevel.AUDIT, category, message, subjectId); }

    public List<AppEvent> recent() {
        return repo.findTop200ByOrderByCreatedAtDesc();
    }

    private void record(EventLevel level, String category, String message, String subjectId) {
        String safe = Redactor.redact(message);
        log.info("[{}] {} {} {}", level, category, subjectId == null ? "-" : subjectId, safe);
        try {
            AppEvent e = new AppEvent();
            e.setId(UUID.randomUUID().toString());
            e.setLevel(level);
            e.setCategory(category);
            e.setMessage(safe.length() > 2000 ? safe.substring(0, 2000) : safe);
            e.setSubjectId(subjectId);
            e.setCreatedAt(System.currentTimeMillis());
            repo.save(e);
        } catch (RuntimeException ex) {
            log.warn("Could not persist event: {}", ex.toString());
        }
    }
}
