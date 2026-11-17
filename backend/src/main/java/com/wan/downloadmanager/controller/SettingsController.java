package com.wan.downloadmanager.controller;

import com.wan.downloadmanager.storage.Settings;
import com.wan.downloadmanager.storage.SettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {
    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public Settings get() {
        return settings.get();
    }

    @PutMapping
    public Settings put(@Valid @RequestBody Settings body) {
        return settings.update(body);
    }

    @PostMapping("/reset")
    public Settings reset() {
        return settings.reset();
    }
}
