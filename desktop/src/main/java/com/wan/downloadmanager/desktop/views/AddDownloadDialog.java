package com.wan.downloadmanager.desktop.views;

import com.wan.downloadmanager.desktop.MainShell;
import com.wan.downloadmanager.desktop.ui.Icons;
import com.wan.downloadmanager.desktop.ui.Wan;
import com.wan.downloadmanager.download.DuplicateDownloadException;
import com.wan.downloadmanager.dto.CreateDownloadRequest;
import com.wan.downloadmanager.entitlement.Feature;
import com.wan.downloadmanager.exception.WdmException;
import com.wan.downloadmanager.queue.QueueService;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;

/** Add New Download: URL, name, folder, connections, queue, start now. Validation errors stay in the dialog. */
public class AddDownloadDialog {
    private final MainShell shell;
    private final String initialUrl;

    public AddDownloadDialog(MainShell shell, String initialUrl) {
        this.shell = shell;
        this.initialUrl = initialUrl;
    }

    public void open() {
        var s = shell.services();
        TextField url = Wan.textField("https://example.com/file.zip");
        if (initialUrl != null) url.setText(initialUrl);
        TextField name = Wan.textField("Automatic (from the server)");
        TextField folder = Wan.textField("Automatic (by category)");
        Button browse = Wan.button("Browse…", Wan.Variant.SECONDARY, Icons.FOLDER, () -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setInitialDirectory(new File(s.settings().get().downloadDir()).exists() ? new File(s.settings().get().downloadDir()) : null);
            File f = dc.showDialog(shell.stage());
            if (f != null) folder.setText(f.getAbsolutePath());
        });
        HBox folderRow = new HBox(8, folder, browse);
        HBox.setHgrow(folder, Priority.ALWAYS);
        boolean multi = s.entitlements().has(Feature.DOWNLOAD_MULTITHREAD);
        int max = s.entitlements().plan().maxConnections();
        Spinner<Integer> conns = new Spinner<>(1, Math.max(1, max), Math.min(s.settings().get().connections(), max));
        conns.setDisable(!multi);
        ComboBox<QueueService.QueueView> queue = Wan.dropdown(s.queues().list(), null);
        queue.getSelectionModel().selectFirst();
        queue.setConverter(new javafx.util.StringConverter<>() {
            public String toString(QueueService.QueueView q) { return q == null ? "" : q.name() + (q.state().equals("RUNNING") ? "" : " (stopped)"); }
            public QueueService.QueueView fromString(String s) { return null; }
        });
        CheckBox start = new CheckBox("Start now");
        start.setSelected(true);
        Label error = Wan.text("", "small");
        error.setStyle("-fx-text-fill: -wan-danger;");
        error.setManaged(false);
        CheckBox allowDup = new CheckBox("Download again even though it is already in the list");
        allowDup.setVisible(false);
        allowDup.setManaged(false);
        VBox form = new VBox(12,
                Wan.field("Address (URL)", url, "http and https links. Links that need your browser login: use the browser extension."),
                Wan.field("File name", name, null),
                Wan.field("Save to", folderRow, null),
                new HBox(16, Wan.field("Connections", conns, multi ? "Used only if the server supports it" : "Multi-connection needs Pro"),
                        Wan.field("Queue", queue, null)),
                start, allowDup, error);
        ButtonType add = new ButtonType("Add download", ButtonBar.ButtonData.OK_DONE);
        Dialog<ButtonType> d = Wan.dialog(shell.stage(), "Add New Download", form, add, ButtonType.CANCEL);
        Button addBtn = (Button) d.getDialogPane().lookupButton(add);
        addBtn.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume(); // keep the dialog open until the backend accepts
            addBtn.setDisable(true);
            var req = new CreateDownloadRequest(url.getText().trim(), blank(name.getText()), blank(folder.getText()), conns.getValue(),
                    queue.getValue() == null ? null : queue.getValue().id(), start.isSelected(), null, null, allowDup.isSelected(), "MANUAL");
            Wan.background(() -> s.downloads().create(req), v -> {
                d.close();
                shell.toast("success", "Download added", v.fileName());
            }, err -> {
                addBtn.setDisable(false);
                error.setManaged(true);
                error.setText(err.getMessage());
                if (err instanceof DuplicateDownloadException) {
                    allowDup.setVisible(true);
                    allowDup.setManaged(true);
                } else if (!(err instanceof WdmException)) {
                    error.setText("Unexpected error: " + err);
                }
            });
        });
        d.setOnShown(e -> url.requestFocus());
        d.show();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
