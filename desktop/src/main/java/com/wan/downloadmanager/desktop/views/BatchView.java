package com.wan.downloadmanager.desktop.views;

import com.wan.downloadmanager.desktop.MainShell;
import com.wan.downloadmanager.desktop.ui.Icons;
import com.wan.downloadmanager.desktop.ui.Wan;
import com.wan.downloadmanager.download.BatchService;
import com.wan.downloadmanager.entitlement.Feature;
import com.wan.downloadmanager.queue.QueueService;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;

/** Batch Download: paste or import many links, preview validation and duplicates, then queue them. */
public class BatchView implements MainShell.View {
    private final MainShell shell;
    private final TextArea text = new TextArea();
    private final VBox result = new VBox(6);

    public BatchView(MainShell shell) {
        this.shell = shell;
    }

    @Override
    public Node build() {
        var s = shell.services();
        text.setPromptText("One link per line. Lines starting with # are ignored.\nhttps://example.com/file1.zip\nhttps://example.com/file2.pdf");
        text.setPrefRowCount(12);
        ComboBox<QueueService.QueueView> queue = Wan.dropdown(s.queues().list(), null);
        queue.getSelectionModel().selectFirst();
        queue.setConverter(new javafx.util.StringConverter<>() {
            public String toString(QueueService.QueueView q) { return q == null ? "" : q.name(); }
            public QueueService.QueueView fromString(String x) { return null; }
        });
        Button importTxt = Wan.button("Import TXT…", Wan.Variant.SECONDARY, Icons.FOLDER, () -> {
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text files", "*.txt"));
            File f = fc.showOpenDialog(shell.stage());
            if (f == null) return;
            try {
                if (Files.size(f.toPath()) > 2_000_000) throw new IllegalArgumentException("File is larger than 2 MB");
                text.setText(Files.readString(f.toPath()));
            } catch (Exception e) {
                shell.toast("error", "Import failed", e.getMessage());
            }
        });
        Button preview = Wan.button("Check links", Wan.Variant.SECONDARY, Icons.SEARCH,
                () -> Wan.background(() -> s.batch().preview(text.getText()), this::show, e -> shell.toast("error", "Batch", e.getMessage())));
        Button add = Wan.button("Add all to queue", Wan.Variant.PRIMARY, Icons.DOWNLOAD,
                () -> Wan.background(() -> s.batch().add(text.getText(), queue.getValue() == null ? null : queue.getValue().id(), null), r -> {
                    show(r);
                    shell.toast("success", "Batch added", r.added() + " downloads added");
                }, e -> shell.toast("error", "Batch", e.getMessage())));
        VBox page = new VBox(16);
        page.getStyleClass().add("page");
        if (!s.entitlements().has(Feature.DOWNLOAD_BATCH)) {
            add.setDisable(true);
            page.getChildren().add(Wan.banner("warn", "Batch download is part of the Pro and Business plans. You can still check links.",
                    Wan.button("See plans", Wan.Variant.PRIMARY, null, () -> shell.go(MainShell.Page.ACCOUNT))));
        }
        HBox actions = new HBox(10, importTxt, preview, Wan.spacer(), Wan.field("Queue", queue, null), add);
        actions.setAlignment(Pos.BOTTOM_LEFT);
        page.getChildren().addAll(Wan.card(Wan.text("Links", "h2"), text, actions), Wan.card(Wan.text("Result", "h2"), result));
        result.getChildren().add(Wan.text("Check the links to see which are valid, duplicated or already downloaded.", "muted"));
        return page;
    }

    private void show(BatchService.Result r) {
        result.getChildren().setAll(new HBox(10, Wan.chip(r.added() + " added", "ok"), Wan.chip(r.duplicates() + " duplicates", "warn"),
                Wan.chip(r.invalid() + " invalid", "err"), Wan.chip(r.lines().size() + " lines", "free")));
        for (BatchService.Line l : r.lines().stream().limit(300).toList()) {
            HBox row = new HBox(10, Wan.text("#" + l.line(), "muted", "small"),
                    Wan.chip(l.status(), switch (l.status()) { case "ADDED", "OK" -> "ok"; case "DUPLICATE" -> "warn"; default -> "err"; }),
                    Wan.text(l.url(), "body"), Wan.spacer(), Wan.text(l.message() == null ? "" : l.message(), "muted", "small"));
            row.setAlignment(Pos.CENTER_LEFT);
            result.getChildren().add(row);
        }
    }
}
