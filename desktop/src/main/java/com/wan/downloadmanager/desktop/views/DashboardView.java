package com.wan.downloadmanager.desktop.views;

import com.wan.downloadmanager.desktop.MainShell;
import com.wan.downloadmanager.desktop.ui.Icons;
import com.wan.downloadmanager.desktop.ui.Wan;
import com.wan.downloadmanager.dto.DownloadView;
import com.wan.downloadmanager.notification.EventBus;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.layout.*;

import java.util.List;
import java.util.Map;

/** Main dashboard: real counts, measured speeds, live speed chart, recent downloads, quick actions. */
public class DashboardView implements MainShell.View {
    private final MainShell shell;
    private final Label active = new Label("0"), activeSub = new Label(), completed = new Label("0"), completedSub = new Label(),
            total = new Label("—"), totalSub = new Label(), speed = new Label("—"), speedSub = new Label(), avg = new Label("—"), avgSub = new Label();
    private final XYChart.Series<Number, Number> series = new XYChart.Series<>();
    private final VBox recent = new VBox(8);
    private final VBox status = new VBox(10);
    private Runnable unsubSpeed, unsubDownloads;
    private long t0 = System.currentTimeMillis();

    public DashboardView(MainShell shell) {
        this.shell = shell;
    }

    @Override
    public Node build() {
        var s = shell.services();
        String name = s.identity().account().profile() != null ? s.identity().account().profile().name() : null;
        Label hello = Wan.text(name != null ? "Welcome back, " + name.split(" ")[0] : "Welcome to WAN Download Manager", "h2");
        hello.setStyle("-fx-text-fill: white;");
        Label sub = Wan.text("Download Everything. Manage Smarter.", "");
        HBox actions = new HBox(10,
                Wan.button("Add download", Wan.Variant.PRIMARY, Icons.PLUS, shell::addDownload),
                Wan.button("Paste URL", Wan.Variant.SECONDARY, Icons.LINK, shell::pasteUrl),
                Wan.button("Video Grabber", Wan.Variant.SECONDARY, Icons.VIDEO, () -> shell.go(MainShell.Page.GRABBER)),
                Wan.button("Batch download", Wan.Variant.SECONDARY, Icons.BATCH, () -> shell.go(MainShell.Page.BATCH)),
                Wan.button("Open downloads folder", Wan.Variant.SECONDARY, Icons.FOLDER, () -> shell.openFolder(s.settings().get().downloadDir())));
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox hero = new VBox(6, hello, sub, new Region(), actions);
        hero.getStyleClass().add("hero-card");

        HBox stats = new HBox(14,
                Wan.statCard(Icons.ACTIVE, "#3b7bff", "Active downloads", active, activeSub),
                Wan.statCard(Icons.DONE, "#16a34a", "Completed", completed, completedSub),
                Wan.statCard(Icons.DISK, "#a78bfa", "Total downloaded", total, totalSub),
                Wan.statCard(Icons.SPEED, "#22d3ee", "Current speed", speed, speedSub),
                Wan.statCard(Icons.HISTORY, "#fbbf24", "Average speed (24 h)", avg, avgSub));

        NumberAxis x = new NumberAxis();
        x.setForceZeroInRange(false);
        x.setTickLabelsVisible(false);
        x.setTickMarkVisible(false);
        NumberAxis y = new NumberAxis();
        y.setLabel("KB/s");
        AreaChart<Number, Number> chart = new AreaChart<>(x, y);
        chart.setLegendVisible(false);
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        chart.setPrefHeight(220);
        chart.getData().add(series);
        VBox chartCard = Wan.card(Wan.text("Download activity (live)", "h3"), chart);
        HBox.setHgrow(chartCard, Priority.ALWAYS);
        VBox side = Wan.card(Wan.text("Status", "h3"), status);
        side.setPrefWidth(340);
        HBox middle = new HBox(14, chartCard, side);

        VBox recentCard = Wan.card(new HBox(Wan.text("Recent downloads", "h3"), Wan.spacer(),
                Wan.button("View all", Wan.Variant.GHOST, null, () -> shell.go(MainShell.Page.ALL))), recent);

        VBox page = new VBox(18, hero, stats, middle, recentCard);
        page.getStyleClass().add("page");

        unsubSpeed = s.bus().subscribe(EventBus.SPEED, p -> {
            if (p instanceof Map<?, ?> m) {
                long bps = ((Number) m.get("bps")).longValue();
                Platform.runLater(() -> {
                    speed.setText(Wan.speed(bps));
                    series.getData().add(new XYChart.Data<>((System.currentTimeMillis() - t0) / 1000.0, bps / 1024.0));
                    if (series.getData().size() > 90) series.getData().removeFirst();
                });
            }
        });
        unsubDownloads = s.bus().subscribe(EventBus.DOWNLOADS, p -> Platform.runLater(this::refreshSoon));
        refresh();
        return page;
    }

    private long lastRefresh;

    private void refreshSoon() {
        if (System.currentTimeMillis() - lastRefresh > 1500) refresh();
    }

    private void refresh() {
        lastRefresh = System.currentTimeMillis();
        var s = shell.services();
        Wan.background(() -> new Object[]{s.stats().dashboard(), s.downloads().list("all"), s.browsers().status(), s.identity().account()}, r -> {
            var d = (com.wan.downloadmanager.stats.StatsService.Dashboard) r[0];
            @SuppressWarnings("unchecked") List<DownloadView> all = (List<DownloadView>) r[1];
            active.setText(String.valueOf(d.active()));
            activeSub.setText(d.queued() + " waiting in queues");
            completed.setText(String.valueOf(d.completed()));
            completedSub.setText(d.failed() + " failed");
            total.setText(Wan.bytes(d.totalDownloadedBytes()));
            totalSub.setText("Measured since install");
            speedSub.setText(d.active() == 0 ? "Idle" : "Across all connections");
            avg.setText(Wan.speed(d.averageSpeedBps()));
            avgSub.setText(d.activity().isEmpty() ? "No traffic in the last 24 h" : d.activity().size() + " active minutes");
            recent.getChildren().clear();
            if (all.isEmpty()) {
                recent.getChildren().add(Wan.empty(Icons.DOWNLOAD, "No downloads yet", "Paste a link or use the browser extension to start.",
                        Wan.button("Add download", Wan.Variant.PRIMARY, Icons.PLUS, shell::addDownload)));
            }
            for (DownloadView v : all.stream().limit(5).toList()) recent.getChildren().add(recentRow(v));
            status.getChildren().clear();
            @SuppressWarnings("unchecked") var browsers = (List<com.wan.downloadmanager.browser.BrowserService.BrowserStatus>) r[2];
            for (var b : browsers) {
                status.getChildren().add(new HBox(8, Wan.text(cap(b.browser()), "body"), Wan.spacer(),
                        Wan.chip(!b.enabled() ? "Off" : b.connected() ? "Connected" : "Not connected", !b.enabled() ? "free" : b.connected() ? "ok" : "warn")));
            }
            var acct = (com.wan.downloadmanager.identity.IdentityService.AccountView) r[3];
            status.getChildren().add(new javafx.scene.control.Separator());
            status.getChildren().add(new HBox(8, Wan.text("WAN account", "body"), Wan.spacer(),
                    Wan.chip(switch (acct.status()) {
                        case SIGNED_IN -> "Signed in";
                        case OFFLINE -> "Offline";
                        case SIGNING_IN -> "Signing in…";
                        case NOT_CONFIGURED -> "Not configured";
                        default -> "Signed out";
                    }, acct.status() == com.wan.downloadmanager.identity.IdentityService.Status.SIGNED_IN ? "ok" : "warn")));
            status.getChildren().add(new HBox(8, Wan.text("Plan", "body"), Wan.spacer(),
                    Wan.chip(cap(acct.entitlement().tier().name()), acct.entitlement().tier().name().toLowerCase())));
        }, e -> shell.toast("error", "Dashboard", e.getMessage()));
    }

    private Node recentRow(DownloadView v) {
        Wan.Progress p = new Wan.Progress();
        p.set(v.progress() == null ? -1 : v.progress(), DownloadsView.progressState(v.status()));
        p.setPrefWidth(180);
        Label name = Wan.text(v.fileName(), "h3");
        name.setWrapText(false);
        HBox row = new HBox(12, Icons.icon(Icons.DOWNLOAD, 18, javafx.scene.paint.Color.web("#3b7bff")),
                new VBox(2, name, Wan.text(Wan.bytes(v.totalBytes()) + " · " + v.status().toLowerCase(), "muted", "small")), Wan.spacer(), p,
                DownloadsView.statusChip(v.status()));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    static String cap(String s) {
        return s.charAt(0) + s.substring(1).toLowerCase();
    }

    @Override
    public void dispose() {
        if (unsubSpeed != null) unsubSpeed.run();
        if (unsubDownloads != null) unsubDownloads.run();
    }
}
