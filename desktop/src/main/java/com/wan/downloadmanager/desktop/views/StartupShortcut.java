package com.wan.downloadmanager.desktop.views;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * "Start with Windows": a small .cmd in the user's Startup folder that launches the installed app
 * (path of the running jpackage launcher). No registry writes, removable by the user. No-op elsewhere.
 */
final class StartupShortcut {
    private StartupShortcut() {}

    static void apply(boolean enabled) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        String appData = System.getenv("APPDATA");
        if (appData == null) return;
        Path file = Path.of(appData, "Microsoft", "Windows", "Start Menu", "Programs", "Startup", "WAN Download Manager.cmd");
        if (!enabled) {
            Files.deleteIfExists(file);
            return;
        }
        String exe = ProcessHandle.current().info().command().orElse(null);
        if (exe == null || !exe.toLowerCase().endsWith(".exe") || exe.toLowerCase().endsWith("java.exe")) return; // dev run: nothing to register
        Files.writeString(file, "@echo off\r\nstart \"\" \"" + exe + "\"\r\n");
    }
}
