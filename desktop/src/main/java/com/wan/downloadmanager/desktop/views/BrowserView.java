package com.wan.downloadmanager.desktop.views;

import com.wan.downloadmanager.browser.BrowserService;
import com.wan.downloadmanager.desktop.MainShell;
import com.wan.downloadmanager.desktop.ui.Icons;
import com.wan.downloadmanager.desktop.ui.Wan;
import com.wan.downloadmanager.storage.Settings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Browser Integration: per-browser status (last native-messaging contact), switches and diagnostics. */
public class BrowserView implements MainShell.View {
    private final MainShell shell;
    private final VBox cards = new VBox(12);

    public BrowserView(MainShell shell) {
        this.shell = shell;
    }

    @Override
    public Node build() {
        VBox page = new VBox(16);
        page.getStyleClass().add("page");
        Path runtime = Path.of(shell.services().dataDir(), "runtime.json");
        Path allowed = Path.of(shell.services().dataDir(), "allowed-origins.properties");
        VBox diag = Wan.card(Wan.text("Diagnostics", "h2"),
                row("Local API", "127.0.0.1:" + shell.services().api().port() + " (token protected, no browser origins)", true),
                row("Runtime file for the native host", runtime.toString(), Files.exists(runtime)),
                row("Developer allow-list for unpacked extensions", allowed.toString(), Files.exists(allowed)),
                Wan.text("The installer registers the native host for Chrome, Edge and Firefox (HKCU, no admin rights). "
                        + "If a browser shows \"not connected\", open its extension popup once; it connects on demand.", "muted"));
        page.getChildren().addAll(cards, diag);
        reload();
        return page;
    }

    private Node row(String title, String value, boolean ok) {
        HBox h = new HBox(10, Wan.chip(ok ? "OK" : "Missing", ok ? "ok" : "warn"), Wan.text(title, "h3"), Wan.spacer(), Wan.text(value, "muted", "small"));
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private void reload() {
        Wan.background(() -> shell.services().browsers().status(), list -> {
            cards.getChildren().clear();
            HBox grid = new HBox(14);
            for (BrowserService.BrowserStatus b : list) grid.getChildren().add(card(b));
            cards.getChildren().add(grid);
        }, e -> shell.toast("error", "Browser integration", e.getMessage()));
    }

    private Node card(BrowserService.BrowserStatus b) {
        Settings st = shell.services().settings().get();
        Wan.Toggle t = new Wan.Toggle(b.enabled(), on -> {
            Settings n = switch (b.browser()) {
                case "CHROME" -> with(st, on, st.edgeEnabled(), st.firefoxEnabled());
                case "EDGE" -> with(st, st.chromeEnabled(), on, st.firefoxEnabled());
                default -> with(st, st.chromeEnabled(), st.edgeEnabled(), on);
            };
            Wan.background(() -> shell.services().settings().update(n), x -> reload(), e -> shell.toast("error", "Settings", e.getMessage()));
        });
        String name = DashboardView.cap(b.browser());
        VBox c = Wan.card(new HBox(10, Icons.icon(Icons.BROWSER, 22, javafx.scene.paint.Color.web("#3b7bff")), Wan.text(name, "h2"), Wan.spacer(), t),
                Wan.chip(!b.enabled() ? "Turned off" : b.connected() ? "Connected" : "Not connected", !b.enabled() ? "free" : b.connected() ? "ok" : "warn"),
                Wan.text(b.extensionVersion() == null ? "Extension not seen yet" : "Extension " + b.extensionVersion(), "muted"),
                Wan.text(b.lastSeenAt() == null ? "" : "Last contact " + Instant.ofEpochMilli(b.lastSeenAt()), "muted", "small"));
        HBox.setHgrow(c, Priority.ALWAYS);
        return c;
    }

    static Settings with(Settings s, boolean chrome, boolean edge, boolean firefox) {
        return new Settings(s.theme(), s.language(), s.startWithWindows(), s.startMinimized(), s.minimizeToTray(), s.notifications(),
                s.downloadDir(), s.connections(), s.maxConcurrent(), s.globalSpeedLimitBps(), s.retryCount(), s.autoCategorize(), s.duplicateCheck(),
                chrome, edge, firefox, s.videoDetection(), s.videoPopup(), s.blockedSites() == null ? List.of() : s.blockedSites(),
                s.ffmpegPath(), s.ffprobePath(), s.conversionFormat(), s.mediaOutputDir());
    }
}
