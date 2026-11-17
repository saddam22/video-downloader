package com.wan.downloadmanager.desktop.views;

import com.wan.downloadmanager.desktop.MainShell;
import com.wan.downloadmanager.desktop.ui.Icons;
import com.wan.downloadmanager.desktop.ui.Wan;
import com.wan.downloadmanager.dto.DownloadView;
import com.wan.downloadmanager.notification.EventBus;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** All / Active / Completed downloads with live progress and per-row actions. */
public class DownloadsView implements MainShell.View {
    private final MainShell shell;
    private final String filter;
    private final ObservableList<DownloadView> rows = FXCollections.observableArrayList();
    private final FilteredList<DownloadView> filtered = new FilteredList<>(rows, d -> true);
    private final TextField search = Wan.textField("Filter by name or address…");
    private final TableView<DownloadView> table = new TableView<>(filtered);
    private Runnable unsub;

    public DownloadsView(MainShell shell, String filter) {
        this.shell = shell;
        this.filter = filter;
    }

    @Override
    public boolean fillHeight() {
        return true;
    }

    public void search(String q) {
        search.setText(q);
    }

    @Override
    public Node build() {
        var s = shell.services();
        search.textProperty().addListener((o, a, q) -> filtered.setPredicate(d -> q == null || q.isBlank()
                || d.fileName().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))
                || d.url().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))));
        HBox.setHgrow(search, Priority.ALWAYS);
        HBox toolbar = new HBox(10,
                Wan.button("Add", Wan.Variant.PRIMARY, Icons.PLUS, shell::addDownload),
                Wan.button("Resume all", Wan.Variant.SECONDARY, Icons.PLAY, () -> act(() -> s.downloads().resumeAll())),
                Wan.button("Pause all", Wan.Variant.SECONDARY, Icons.PAUSE, () -> act(() -> s.downloads().pauseAll())),
                Wan.button("Retry failed", Wan.Variant.SECONDARY, Icons.RETRY, () -> act(() -> s.downloads().retryFailed())),
                search);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        TableColumn<DownloadView, DownloadView> name = col("Name", 320, d -> d);
        name.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(DownloadView d, boolean empty) {
                super.updateItem(d, empty);
                if (empty || d == null) {
                    setGraphic(null);
                    return;
                }
                Label n = Wan.text(d.fileName(), "h3");
                n.setWrapText(false);
                Label host = Wan.text(hostOf(d.url()) + (d.source().equals("MANUAL") ? "" : " · " + d.source().toLowerCase(Locale.ROOT)), "muted", "small");
                host.setWrapText(false);
                setGraphic(new VBox(1, n, host));
            }
        });
        TableColumn<DownloadView, DownloadView> size = col("Size", 110, d -> d);
        size.setCellFactory(c -> textCell(d -> d.totalBytes() == null ? Wan.bytes(d.downloadedBytes()) + " / ?" : Wan.bytes(d.totalBytes())));
        TableColumn<DownloadView, DownloadView> progress = col("Progress", 200, d -> d);
        progress.setCellFactory(c -> new TableCell<>() {
            private final Wan.Progress bar = new Wan.Progress();
            private final Label pct = Wan.text("", "muted", "small");
            { pct.setWrapText(false); }
            private final VBox box = new VBox(4, bar, pct);

            @Override
            protected void updateItem(DownloadView d, boolean empty) {
                super.updateItem(d, empty);
                if (empty || d == null) {
                    setGraphic(null);
                    return;
                }
                bar.set(d.progress() == null ? -1 : d.progress(), progressState(d.status()));
                pct.setText(d.progress() == null ? Wan.bytes(d.downloadedBytes()) + " received (size unknown)"
                        : String.format(Locale.ROOT, "%.1f%%", d.progress() * 100)
                        + (d.activeConnections() > 1 ? " · " + d.activeConnections() + " connections" : ""));
                setGraphic(box);
            }
        });
        TableColumn<DownloadView, DownloadView> speed = col("Speed", 100, d -> d);
        speed.setCellFactory(c -> textCell(d -> d.status().equals("DOWNLOADING") ? Wan.speed(d.speedBps()) : "—"));
        TableColumn<DownloadView, DownloadView> eta = col("Time left", 100, d -> d);
        eta.setCellFactory(c -> textCell(d -> d.status().equals("DOWNLOADING") ? Wan.eta(d.etaSeconds()) : "—"));
        TableColumn<DownloadView, DownloadView> status = col("Status", 120, d -> d);
        status.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(DownloadView d, boolean empty) {
                super.updateItem(d, empty);
                setGraphic(empty || d == null ? null : statusChip(d.status()));
                if (d != null && d.errorMessage() != null) setTooltip(new Tooltip(d.errorMessage()));
            }
        });
        TableColumn<DownloadView, DownloadView> actions = col("", 170, d -> d);
        actions.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(DownloadView d, boolean empty) {
                super.updateItem(d, empty);
                setGraphic(empty || d == null ? null : actionsFor(d));
            }
        });
        table.getColumns().setAll(List.of(name, size, progress, speed, eta, status, actions));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setFixedCellSize(58);
        table.setPlaceholder(Wan.empty(Icons.DOWNLOAD, emptyTitle(), "Add a link, paste one from the clipboard, or send one from your browser.",
                Wan.button("Add download", Wan.Variant.PRIMARY, Icons.PLUS, shell::addDownload)));
        table.setRowFactory(t -> {
            TableRow<DownloadView> r = new TableRow<>();
            r.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && r.getItem() != null) details(r.getItem());
            });
            r.setContextMenu(contextMenu(r));
            return r;
        });
        VBox.setVgrow(table, Priority.ALWAYS);
        VBox page = new VBox(14, toolbar, table);
        page.getStyleClass().add("page");

        unsub = s.bus().subscribe(EventBus.DOWNLOADS, p -> Platform.runLater(() -> onEvent(p)));
        reload();
        return page;
    }

    private String emptyTitle() {
        return switch (filter) {
            case "active" -> "Nothing downloading";
            case "completed" -> "No completed downloads yet";
            default -> "No downloads yet";
        };
    }

    private void reload() {
        Wan.background(() -> shell.services().downloads().list(filter), rows::setAll, e -> shell.toast("error", "Downloads", e.getMessage()));
    }

    private void onEvent(Object p) {
        if (p instanceof DownloadView v) {
            boolean belongs = switch (filter) {
                case "active" -> List.of("QUEUED", "DOWNLOADING", "PAUSED").contains(v.status());
                case "completed" -> v.status().equals("COMPLETED");
                default -> true;
            };
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).id().equals(v.id())) {
                    if (belongs) rows.set(i, v);
                    else rows.remove(i);
                    return;
                }
            }
            if (belongs) rows.addFirst(v);
        } else if (p instanceof Map<?, ?> m && m.get("deleted") != null) {
            rows.removeIf(r -> r.id().equals(m.get("deleted")));
        }
    }

    private Node actionsFor(DownloadView d) {
        var s = shell.services().downloads();
        HBox h = new HBox(2);
        h.setAlignment(Pos.CENTER_RIGHT);
        switch (d.status()) {
            case "DOWNLOADING", "QUEUED" -> h.getChildren().add(Wan.iconButton(Icons.PAUSE, "Pause", () -> act(() -> s.pause(d.id()))));
            case "PAUSED" -> h.getChildren().add(Wan.iconButton(Icons.PLAY, "Resume", () -> act(() -> s.resume(d.id()))));
            case "FAILED", "CANCELLED" -> h.getChildren().add(Wan.iconButton(Icons.RETRY, "Retry", () -> act(() -> s.retry(d.id()))));
            case "COMPLETED" -> h.getChildren().add(Wan.iconButton(Icons.FOLDER, "Open folder", () -> shell.openFolder(d.saveDir())));
            default -> { }
        }
        if (!d.status().equals("COMPLETED") && !d.status().equals("CANCELLED")) {
            h.getChildren().add(Wan.iconButton(Icons.STOP, "Cancel (deletes the partial file)", () -> act(() -> {
                try {
                    s.cancel(d.id());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            })));
        }
        h.getChildren().add(Wan.iconButton(Icons.TRASH, "Remove from list", () -> remove(d)));
        return h;
    }

    private ContextMenu contextMenu(TableRow<DownloadView> r) {
        MenuItem open = new MenuItem("Open file");
        open.setOnAction(e -> {
            DownloadView d = r.getItem();
            if (d != null && d.filePath() != null) shell.host().showDocument(Path.of(d.filePath()).toUri().toString());
        });
        MenuItem folder = new MenuItem("Open folder");
        folder.setOnAction(e -> { if (r.getItem() != null) shell.openFolder(r.getItem().saveDir()); });
        MenuItem copy = new MenuItem("Copy address");
        copy.setOnAction(e -> {
            if (r.getItem() == null) return;
            ClipboardContent cc = new ClipboardContent();
            cc.putString(r.getItem().url());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
        });
        MenuItem info = new MenuItem("Details…");
        info.setOnAction(e -> { if (r.getItem() != null) details(r.getItem()); });
        return new ContextMenu(open, folder, copy, info);
    }

    private void remove(DownloadView d) {
        boolean completed = d.status().equals("COMPLETED");
        CheckBox alsoFile = new CheckBox("Also delete the downloaded file");
        alsoFile.setVisible(completed);
        ButtonType ok = new ButtonType("Remove", ButtonBar.ButtonData.OK_DONE);
        var dlg = Wan.dialog(shell.stage(), "Remove " + d.fileName() + "?", new VBox(10,
                Wan.text("The download is removed from the list. A partial file is deleted.", "body"), alsoFile), ok, ButtonType.CANCEL);
        dlg.showAndWait().filter(b -> b == ok).ifPresent(b -> act(() -> {
            try {
                shell.services().downloads().delete(d.id(), alsoFile.isSelected());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    private void details(DownloadView d) {
        GridPane g = new GridPane();
        g.getStyleClass().add("gap-sm");
        String[][] rowsData = {
                {"Status", d.status() + (d.errorMessage() != null ? " — " + d.errorMessage() : "")},
                {"Address", d.url()}, {"Page", d.pageUrl() == null ? "—" : d.pageUrl()},
                {"Saved in", d.saveDir()}, {"Size", Wan.bytes(d.totalBytes())}, {"Received", Wan.bytes(d.downloadedBytes())},
                {"Type", d.mimeType() == null ? "—" : d.mimeType()},
                {"Connections", d.connections() + (d.resumable() ? " (server supports resume)" : " (server does not support resume)")},
                {"SHA-256", d.sha256() == null ? "—" : d.sha256()}};
        for (int i = 0; i < rowsData.length; i++) {
            g.add(Wan.text(rowsData[i][0], "field-label"), 0, i);
            Label v = Wan.text(rowsData[i][1], "body");
            v.setMaxWidth(420);
            g.add(v, 1, i);
        }
        Wan.dialog(shell.stage(), d.fileName(), g, ButtonType.CLOSE).show();
    }

    private void act(Runnable r) {
        Wan.background(() -> {
            r.run();
            return true;
        }, ok -> { }, e -> shell.toast("error", "Action failed", e.getMessage()));
    }

    static Label statusChip(String status) {
        return switch (status) {
            case "DOWNLOADING" -> Wan.chip("Downloading", "info");
            case "QUEUED" -> Wan.chip("Waiting", "free");
            case "PAUSED" -> Wan.chip("Paused", "warn");
            case "COMPLETED" -> Wan.chip("Complete", "ok");
            case "FAILED" -> Wan.chip("Failed", "err");
            default -> Wan.chip("Cancelled", "free");
        };
    }

    static String progressState(String status) {
        return switch (status) {
            case "COMPLETED" -> "done";
            case "PAUSED" -> "paused";
            case "FAILED" -> "failed";
            default -> null;
        };
    }

    static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private static <T> TableColumn<T, T> col(String title, double width, java.util.function.Function<T, T> f) {
        TableColumn<T, T> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(f.apply(cd.getValue())));
        c.setSortable(false);
        return c;
    }

    private static TableCell<DownloadView, DownloadView> textCell(java.util.function.Function<DownloadView, String> f) {
        return new TableCell<>() {
            @Override
            protected void updateItem(DownloadView d, boolean empty) {
                super.updateItem(d, empty);
                setText(empty || d == null ? null : f.apply(d));
            }
        };
    }

    @Override
    public void dispose() {
        if (unsub != null) unsub.run();
    }
}
