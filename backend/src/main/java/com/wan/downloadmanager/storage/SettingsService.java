package com.wan.downloadmanager.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wan.downloadmanager.entity.AppSetting;
import com.wan.downloadmanager.exception.WdmException;
import com.wan.downloadmanager.repository.AppSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Settings persisted as one JSON document under key {@code settings} (forward compatible: unknown
 * keys are dropped, missing keys take defaults). Listeners are told about every change.
 */
@Service
public class SettingsService {
    private static final String KEY = "settings";
    private final AppSettingRepository repo;
    private final ObjectMapper mapper;
    private final List<Consumer<Settings>> listeners = new CopyOnWriteArrayList<>();
    private volatile Settings cached;

    public SettingsService(AppSettingRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public static Settings defaults() {
        String downloads = Path.of(System.getProperty("user.home"), "Downloads", "WAN").toString();
        return new Settings("SYSTEM", "en", false, false, true, true,
                downloads, 8, 3, 0, 5, true, true,
                true, true, true, true, true, List.of(),
                "", "", "mp4", downloads);
    }

    public Settings get() {
        Settings s = cached;
        if (s != null) return s;
        synchronized (this) {
            if (cached == null) cached = load();
            return cached;
        }
    }

    @Transactional
    public Settings update(Settings next) {
        if (next.blockedSites() != null && next.blockedSites().size() > 500) {
            throw WdmException.badRequest("too_many_sites", "At most 500 blocked sites");
        }
        try {
            String json = mapper.writeValueAsString(next);
            AppSetting row = repo.findById(KEY).orElseGet(() -> new AppSetting(KEY, json, 0));
            row.setValue(json);
            row.setUpdatedAt(System.currentTimeMillis());
            repo.save(row);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        cached = next;
        listeners.forEach(l -> l.accept(next));
        return next;
    }

    @Transactional
    public Settings reset() {
        return update(defaults());
    }

    public void onChange(Consumer<Settings> listener) {
        listeners.add(listener);
    }

    private Settings load() {
        Settings def = defaults();
        return repo.findById(KEY).map(row -> {
            try {
                ObjectNode merged = mapper.valueToTree(def);
                var stored = mapper.readTree(row.getValue());
                stored.fieldNames().forEachRemaining(f -> {
                    if (merged.has(f)) merged.set(f, stored.get(f));
                });
                return mapper.treeToValue(merged, Settings.class);
            } catch (Exception e) {
                return def;
            }
        }).orElse(def);
    }
}
