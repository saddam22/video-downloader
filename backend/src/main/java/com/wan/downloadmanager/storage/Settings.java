package com.wan.downloadmanager.storage;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/** All user settings. Defaults live in {@link SettingsService#defaults()}. */
public record Settings(
        // General
        @Pattern(regexp = "SYSTEM|DARK|LIGHT") String theme,
        @Pattern(regexp = "en|bn") String language,
        boolean startWithWindows,
        boolean startMinimized,
        boolean minimizeToTray,
        boolean notifications,
        // Downloads
        @NotBlank String downloadDir,
        @Min(1) @Max(16) int connections,
        @Min(1) @Max(16) int maxConcurrent,
        @Min(0) long globalSpeedLimitBps,
        @Min(0) @Max(50) int retryCount,
        boolean autoCategorize,
        boolean duplicateCheck,
        // Browser
        boolean chromeEnabled,
        boolean edgeEnabled,
        boolean firefoxEnabled,
        boolean videoDetection,
        boolean videoPopup,
        List<String> blockedSites,
        // Media
        String ffmpegPath,
        String ffprobePath,
        @Pattern(regexp = "mp4|mkv|webm|mp3|m4a|aac|wav|flac") String conversionFormat,
        String mediaOutputDir) {
}
