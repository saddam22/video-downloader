package com.wan.downloadmanager.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Resolves {@code wan.data-dir} before the datasource and logging start: an explicit property wins,
 * otherwise the per-user application data folder (%LOCALAPPDATA%\WAN\DownloadManager on Windows,
 * ~/.local/share/wan-download-manager elsewhere). Creates it with owner-only permissions where supported.
 */
public class DataDirEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String configured = env.getProperty("wan.data-dir");
        Path dir = configured != null && !configured.isBlank() ? Path.of(configured) : defaultDataDir();
        try {
            Files.createDirectories(dir.resolve("logs"));
            FilePermissions.restrictToOwner(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create data directory " + dir, e);
        }
        env.getPropertySources().addFirst(new MapPropertySource("wanDataDir",
                Map.of("wan.data-dir", dir.toAbsolutePath().toString().replace('\\', '/'))));
    }

    public static Path defaultDataDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            if (local != null && !local.isBlank()) return Path.of(local, "WAN", "DownloadManager");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isBlank()) return Path.of(xdg, "wan-download-manager");
        return Path.of(System.getProperty("user.home"), ".local", "share", "wan-download-manager");
    }
}
