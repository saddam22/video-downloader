package com.wan.downloadmanager.desktop.views;

import com.wan.downloadmanager.desktop.MainShell;
import com.wan.downloadmanager.desktop.ui.Icons;
import com.wan.downloadmanager.desktop.ui.Wan;
import com.wan.downloadmanager.entity.AppEvent;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** About, Update Center (manual: no update service exists yet) and Diagnostics (versions, paths, redacted event log). */
public class AboutView implements MainShell.View {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private final MainShell shell;

    public AboutView(MainShell shell) {
        this.shell = shell;
    }

    @Override
    public Node build() {
        var s = shell.services();
        String version = getClass().getPackage().getImplementationVersion();
        VBox about = Wan.card(new HBox(14, MainShell.logo(56), new VBox(4, Wan.text("WAN Download Manager", "h1"),
                        Wan.text("Version " + (version == null ? "0.1.0 (development build)" : version) + " · Download Everything. Manage Smarter.", "muted"),
                        Wan.text("Part of the WAN ecosystem. Sign-in by WAN Identity Hub. Design based on WANBAHJAHKAS LTD.", "muted"))),
                Wan.text("Update Center: automatic updates need the planned WAN Update Service. Install new versions with the latest installer; "
                        + "settings, downloads and history are kept.", "body"));
        javafx.scene.control.Label ff = Wan.text("FFmpeg: checking…", "body");
        Wan.background(() -> s.ffmpeg().detect(), t -> ff.setText("FFmpeg: " + t.map(x -> x.version() + " (" + x.ffmpeg() + ")").orElse("not found")),
                e -> ff.setText("FFmpeg: " + e.getMessage()));
        VBox diag = Wan.card(Wan.text("Diagnostics", "h2"),
                Wan.text("Java " + System.getProperty("java.version") + " · JavaFX " + System.getProperty("javafx.version") + " · "
                        + System.getProperty("os.name") + " " + System.getProperty("os.version"), "body"),
                Wan.text("Data folder: " + s.dataDir(), "body"), Wan.text("Local API: 127.0.0.1:" + s.api().port(), "body"), ff,
                Wan.text("Active downloads: " + s.engine().activeCount() + " · Global limit: " + Wan.speed(s.engine().globalLimitBps()), "body"),
                new HBox(10, Wan.button("Open logs folder", Wan.Variant.SECONDARY, Icons.FOLDER, () -> shell.openFolder(s.dataDir() + "/logs"))));
        VBox events = new VBox(4);
        Wan.background(() -> s.events().recent(), list -> {
            for (AppEvent e : list.stream().limit(80).toList()) {
                HBox row = new HBox(10, Wan.text(FMT.format(Instant.ofEpochMilli(e.getCreatedAt())), "muted", "small", "mono"),
                        Wan.chip(e.getLevel().name(), switch (e.getLevel()) { case ERROR -> "err"; case WARN -> "warn"; case AUDIT -> "info"; default -> "free"; }),
                        Wan.text(e.getCategory(), "field-label"), Wan.text(e.getMessage(), "body"));
                row.setAlignment(Pos.CENTER_LEFT);
                events.getChildren().add(row);
            }
            if (list.isEmpty()) events.getChildren().add(Wan.text("No events yet.", "muted"));
        }, e -> events.getChildren().add(Wan.text(e.getMessage(), "muted")));
        VBox page = new VBox(16, about, diag, Wan.card(Wan.text("Recent events (secrets redacted)", "h2"), events));
        page.getStyleClass().add("page");
        return page;
    }
}
