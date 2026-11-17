package com.wan.downloadmanager.desktop.views;

import com.wan.downloadmanager.desktop.MainShell;
import com.wan.downloadmanager.desktop.ui.Icons;
import com.wan.downloadmanager.desktop.ui.Wan;
import com.wan.downloadmanager.entity.HistoryEntry;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

/** Download History: search, status filter, CSV export, clear. */
public class HistoryView implements MainShell.View {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final MainShell shell;
    private final TableView<HistoryEntry> table = new TableView<>();
    private final TextField q = Wan.textField("Search file name or address…");
    private final ComboBox<String> status = Wan.dropdown(List.of("All", "COMPLETED", "FAILED", "CANCELLED"), "All");

    public HistoryView(MainShell shell) {
        this.shell = shell;
    }

    @Override
    public boolean fillHeight() {
        return true;
    }

    @Override
    public Node build() {
        q.setOnAction(e -> reload());
        status.setOnAction(e -> reload());
        status.setMaxWidth(160);
        HBox.setHgrow(q, Priority.ALWAYS);
        HBox bar = new HBox(10, q, status, Wan.button("Search", Wan.Variant.SECONDARY, Icons.SEARCH, this::reload),
                Wan.button("Export CSV", Wan.Variant.SECONDARY, Icons.DOWNLOAD, this::export),
                Wan.button("Clear history", Wan.Variant.DANGER, Icons.TRASH, () -> {
                    if (Wan.confirm(shell.stage(), "Clear history?", "Downloaded files are not touched.", "Clear")) {
                        Wan.background(() -> {
                            shell.services().history().clear();
                            return true;
                        }, ok -> reload(), e -> shell.toast("error", "History", e.getMessage()));
                    }
                }));
        bar.setAlignment(Pos.CENTER_LEFT);
        table.getColumns().setAll(List.of(col("File", 260, HistoryEntry::getFileName), col("Type", 70, h -> h.getFileType() == null ? "" : h.getFileType()),
                col("Size", 90, h -> Wan.bytes(h.getSizeBytes())), col("Status", 100, HistoryEntry::getStatus),
                col("Finished", 140, h -> FMT.format(Instant.ofEpochMilli(h.getCompletedAt()))),
                col("Saved to", 260, h -> h.getSavePath() == null ? "—" : h.getSavePath()), col("Address", 260, HistoryEntry::getUrl)));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setFixedCellSize(58);
        table.setPlaceholder(Wan.empty(Icons.HISTORY, "No history", "Finished and failed downloads appear here.", null));
        VBox.setVgrow(table, Priority.ALWAYS);
        VBox page = new VBox(14, bar, table);
        page.getStyleClass().add("page");
        reload();
        return page;
    }

    private String statusFilter() {
        return "All".equals(status.getValue()) ? null : status.getValue();
    }

    private void reload() {
        Wan.background(() -> shell.services().history().search(q.getText(), statusFilter(), 2000), table.getItems()::setAll,
                e -> shell.toast("error", "History", e.getMessage()));
    }

    private void export() {
        FileChooser fc = new FileChooser();
        fc.setInitialFileName("wan-download-history.csv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File f = fc.showSaveDialog(shell.stage());
        if (f == null) return;
        Wan.background(() -> {
            try {
                Files.writeString(f.toPath(), shell.services().history().exportCsv(q.getText(), statusFilter()), StandardCharsets.UTF_8);
                return f;
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }, x -> shell.toast("success", "Exported", x.getName()), e -> shell.toast("error", "Export failed", e.getMessage()));
    }

    private static TableColumn<HistoryEntry, String> col(String t, double w, Function<HistoryEntry, String> f) {
        TableColumn<HistoryEntry, String> c = new TableColumn<>(t);
        c.setPrefWidth(w);
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(f.apply(cd.getValue())));
        return c;
    }
}
