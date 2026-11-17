package com.wan.downloadmanager.desktop.views;

import com.wan.downloadmanager.desktop.MainShell;
import com.wan.downloadmanager.desktop.ui.Icons;
import com.wan.downloadmanager.desktop.ui.Wan;
import com.wan.downloadmanager.storage.Settings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/** Settings — General, Downloads, Browser, Media, Account, Advanced. Saved through SettingsService (validated). */
public class SettingsView implements MainShell.View {
    private final MainShell shell;

    public SettingsView(MainShell shell) {
        this.shell = shell;
    }

    @Override
    public Node build() {
        Settings s = shell.services().settings().get();
        // General
        ComboBox<String> theme = Wan.dropdown(List.of("SYSTEM", "DARK", "LIGHT"), s.theme());
        ComboBox<String> lang = Wan.dropdown(List.of("en", "bn"), s.language());
        Wan.Toggle startup = new Wan.Toggle(s.startWithWindows(), null);
        Wan.Toggle startMin = new Wan.Toggle(s.startMinimized(), null);
        Wan.Toggle tray = new Wan.Toggle(s.minimizeToTray(), null);
        Wan.Toggle notify = new Wan.Toggle(s.notifications(), null);
        // Downloads
        TextField dir = Wan.textField("");
        dir.setText(s.downloadDir());
        Spinner<Integer> conns = new Spinner<>(1, 16, s.connections());
        Spinner<Integer> conc = new Spinner<>(1, 16, s.maxConcurrent());
        TextField limit = Wan.textField("0 = unlimited");
        limit.setText(String.valueOf(s.globalSpeedLimitBps() / 1024));
        Spinner<Integer> retries = new Spinner<>(0, 50, s.retryCount());
        Wan.Toggle categorize = new Wan.Toggle(s.autoCategorize(), null);
        Wan.Toggle dup = new Wan.Toggle(s.duplicateCheck(), null);
        // Browser
        Wan.Toggle chrome = new Wan.Toggle(s.chromeEnabled(), null), edge = new Wan.Toggle(s.edgeEnabled(), null), firefox = new Wan.Toggle(s.firefoxEnabled(), null);
        Wan.Toggle detect = new Wan.Toggle(s.videoDetection(), null), popup = new Wan.Toggle(s.videoPopup(), null);
        TextArea blocked = new TextArea(String.join("\n", s.blockedSites() == null ? List.of() : s.blockedSites()));
        blocked.setPrefRowCount(4);
        blocked.setPromptText("One site per line, e.g. bank.example (subdomains included)");
        // Media
        TextField ffmpeg = Wan.textField("Automatic (from PATH)"), ffprobe = Wan.textField("Automatic (from PATH)"), mediaOut = Wan.textField("");
        ffmpeg.setText(s.ffmpegPath());
        ffprobe.setText(s.ffprobePath());
        mediaOut.setText(s.mediaOutputDir());
        ComboBox<String> fmt = Wan.dropdown(List.of("mp4", "mkv", "webm", "mp3", "m4a", "aac", "wav", "flac"), s.conversionFormat());

        TabPane tabs = new TabPane(
                tab("General", Wan.field("Theme", theme, null), Wan.field("Language", lang, "Bengali interface strings are partial (see KNOWN-ISSUES)"),
                        Wan.toggleRow("Start with Windows", "Adds a shortcut to your Startup folder", startup),
                        Wan.toggleRow("Start minimised", null, startMin), Wan.toggleRow("Minimise to tray on close", null, tray),
                        Wan.toggleRow("Notifications", "Toasts and Windows notifications", notify)),
                tab("Downloads", Wan.field("Default folder", folderRow(dir), null),
                        new HBox(16, Wan.field("Connections per download", conns, "Used only when the server supports ranges"),
                                Wan.field("Downloads at a time", conc, null), Wan.field("Retries", retries, null)),
                        Wan.field("Global speed limit (KB/s)", limit, null),
                        Wan.toggleRow("Sort into category folders", "Video, Music, Documents, Programs…", categorize),
                        Wan.toggleRow("Warn about duplicates", null, dup)),
                tab("Browser", Wan.toggleRow("Chrome", null, chrome), Wan.toggleRow("Edge", null, edge), Wan.toggleRow("Firefox", null, firefox),
                        Wan.toggleRow("Video detection", null, detect), Wan.toggleRow("Floating notice when a video is found", null, popup),
                        Wan.field("Sites where detection is off", blocked, null)),
                tab("Media", Wan.field("FFmpeg executable", fileRow(ffmpeg), null), Wan.field("FFprobe executable", fileRow(ffprobe), null),
                        Wan.field("Default conversion format", fmt, null), Wan.field("Output folder", folderRow(mediaOut), null)),
                tab("Account", Wan.text("Your WAN account, plan, licence and this device are managed on the Account page.", "body"),
                        Wan.button("Open Account", Wan.Variant.PRIMARY, Icons.USER, () -> shell.go(MainShell.Page.ACCOUNT))),
                tab("Advanced", Wan.text("Data folder: " + shell.services().dataDir(), "body"),
                        Wan.text("Database: wdm.db (SQLite) · Logs: logs/wdm.log · Back up by copying the data folder while the app is closed.", "muted"),
                        new HBox(10, Wan.button("Open data folder", Wan.Variant.SECONDARY, Icons.FOLDER, () -> shell.openFolder(shell.services().dataDir())),
                                Wan.button("Diagnostics", Wan.Variant.SECONDARY, Icons.INFO, () -> shell.go(MainShell.Page.ABOUT)),
                                Wan.button("Reset settings", Wan.Variant.DANGER, Icons.RETRY, () -> {
                                    if (Wan.confirm(shell.stage(), "Reset settings?", "Downloads and history are kept.", "Reset")) {
                                        Wan.background(() -> shell.services().settings().reset(), x -> shell.go(MainShell.Page.SETTINGS),
                                                e -> shell.toast("error", "Reset", e.getMessage()));
                                    }
                                }))));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Button save = Wan.button("Save settings", Wan.Variant.PRIMARY, Icons.DONE, () -> {
            long kb;
            try {
                kb = limit.getText().isBlank() ? 0 : Long.parseLong(limit.getText().trim());
            } catch (NumberFormatException e) {
                shell.toast("error", "Settings", "Speed limit must be a whole number");
                return;
            }
            Settings n = new Settings(theme.getValue(), lang.getValue(), startup.isOn(), startMin.isOn(), tray.isOn(), notify.isOn(),
                    dir.getText().trim(), conns.getValue(), conc.getValue(), kb * 1024, retries.getValue(), categorize.isOn(), dup.isOn(),
                    chrome.isOn(), edge.isOn(), firefox.isOn(), detect.isOn(), popup.isOn(),
                    Arrays.stream(blocked.getText().split("\\R")).map(String::trim).filter(x -> !x.isEmpty()).toList(),
                    ffmpeg.getText().trim(), ffprobe.getText().trim(), fmt.getValue(), mediaOut.getText().trim());
            Wan.background(() -> {
                Settings saved = shell.services().settings().update(n);
                try {
                    StartupShortcut.apply(saved.startWithWindows());
                } catch (Exception e) {
                    throw new IllegalStateException("Settings saved, but the startup shortcut could not be changed: " + e.getMessage(), e);
                }
                return saved;
            }, x -> shell.toast("success", "Settings saved", null), e -> shell.toast("error", "Settings not saved", e.getMessage()));
        });
        HBox bar = new HBox(save);
        bar.setAlignment(Pos.CENTER_RIGHT);
        VBox page = new VBox(14, tabs, bar);
        page.getStyleClass().add("page");
        return page;
    }

    private Tab tab(String title, Node... rows) {
        VBox v = Wan.card(rows);
        v.setSpacing(16);
        Tab t = new Tab(title, new VBox(v));
        ((VBox) t.getContent()).setStyle("-fx-padding: 14 0 0 0;");
        return t;
    }

    private Node folderRow(TextField f) {
        HBox h = new HBox(8, f, Wan.button("Browse…", Wan.Variant.SECONDARY, Icons.FOLDER, () -> {
            File d = new DirectoryChooser().showDialog(shell.stage());
            if (d != null) f.setText(d.getAbsolutePath());
        }));
        HBox.setHgrow(f, Priority.ALWAYS);
        return h;
    }

    private Node fileRow(TextField f) {
        HBox h = new HBox(8, f, Wan.button("Browse…", Wan.Variant.SECONDARY, Icons.FOLDER, () -> {
            File d = new FileChooser().showOpenDialog(shell.stage());
            if (d != null) f.setText(d.getAbsolutePath());
        }));
        HBox.setHgrow(f, Priority.ALWAYS);
        return h;
    }
}
