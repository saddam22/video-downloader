package com.wan.downloadmanager.desktop.views;

import com.wan.downloadmanager.desktop.MainShell;
import com.wan.downloadmanager.desktop.ui.Icons;
import com.wan.downloadmanager.desktop.ui.Wan;
import com.wan.downloadmanager.dto.DownloadView;
import com.wan.downloadmanager.queue.QueueService;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;
import java.util.Objects;

/** Queues: start/stop, concurrency, priority, order of waiting downloads. State persists across restarts. */
public class QueueView implements MainShell.View {
    private final MainShell shell;
    private final VBox list = new VBox(12);

    public QueueView(MainShell shell) {
        this.shell = shell;
    }

    @Override
    public Node build() {
        HBox bar = new HBox(10, Wan.button("New queue", Wan.Variant.PRIMARY, Icons.PLUS, this::create), Wan.spacer(),
                Wan.text("Queues run their downloads in order, up to their own limit and the global limit in Settings.", "muted"));
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox page = new VBox(16, bar, list);
        page.getStyleClass().add("page");
        reload();
        return page;
    }

    private void reload() {
        var s = shell.services();
        Wan.background(() -> new Object[]{s.queues().list(), s.downloads().list("all")}, r -> {
            @SuppressWarnings("unchecked") List<QueueService.QueueView> qs = (List<QueueService.QueueView>) r[0];
            @SuppressWarnings("unchecked") List<DownloadView> ds = (List<DownloadView>) r[1];
            list.getChildren().clear();
            for (QueueService.QueueView q : qs) list.getChildren().add(card(q, ds.stream().filter(d -> Objects.equals(d.queueId(), q.id())).toList()));
        }, e -> shell.toast("error", "Queues", e.getMessage()));
    }

    private Node card(QueueService.QueueView q, List<DownloadView> items) {
        var s = shell.services().queues();
        boolean running = q.state().equals("RUNNING");
        Button toggle = running ? Wan.button("Stop queue", Wan.Variant.SECONDARY, Icons.PAUSE, () -> act(() -> s.stop(q.id())))
                : Wan.button("Start queue", Wan.Variant.PRIMARY, Icons.PLAY, () -> act(() -> s.start(q.id())));
        Button edit = Wan.button("Edit", Wan.Variant.GHOST, Icons.SETTINGS, () -> edit(q));
        HBox head = new HBox(10, Wan.text(q.name(), "h2"), Wan.chip(running ? "Running" : "Stopped", running ? "ok" : "free"),
                Wan.text("Up to " + q.maxConcurrent() + " at a time · priority " + q.priority(), "muted"), Wan.spacer(), edit, toggle);
        if (!q.builtIn()) head.getChildren().add(Wan.button("Remove", Wan.Variant.DANGER, Icons.TRASH, () -> {
            if (Wan.confirm(shell.stage(), "Remove queue " + q.name() + "?", "Its downloads move to the main queue.", "Remove")) act(() -> s.delete(q.id()));
        }));
        head.setAlignment(Pos.CENTER_LEFT);
        VBox rows = new VBox(6);
        List<DownloadView> waiting = items.stream().filter(d -> !d.status().equals("COMPLETED")).toList();
        if (waiting.isEmpty()) rows.getChildren().add(Wan.text("Nothing waiting in this queue.", "muted"));
        for (DownloadView d : waiting) {
            HBox row = new HBox(10, DownloadsView.statusChip(d.status()), Wan.text(d.fileName(), "body"), Wan.spacer(),
                    Wan.iconButton(Icons.UP, "Move up", () -> act(() -> s.move(d.id(), -1))),
                    Wan.iconButton(Icons.DOWN, "Move down", () -> act(() -> s.move(d.id(), 1))));
            row.setAlignment(Pos.CENTER_LEFT);
            rows.getChildren().add(row);
        }
        return Wan.card(head, rows);
    }

    private void create() {
        edit(null);
    }

    private void edit(QueueService.QueueView q) {
        TextField name = Wan.textField("Night downloads");
        Spinner<Integer> conc = new Spinner<>(1, 16, q == null ? 3 : q.maxConcurrent());
        Spinner<Integer> prio = new Spinner<>(-100, 100, q == null ? 0 : q.priority());
        if (q != null) name.setText(q.name());
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        var d = Wan.dialog(shell.stage(), q == null ? "New queue" : "Edit queue",
                new VBox(12, Wan.field("Name", name, null), Wan.field("Downloads at a time", conc, null),
                        Wan.field("Priority", prio, "Higher priority queues start first")), save, ButtonType.CANCEL);
        d.showAndWait().filter(b -> b == save).ifPresent(b -> {
            var req = new QueueService.QueueRequest(name.getText(), prio.getValue(), conc.getValue());
            act(() -> {
                if (q == null) shell.services().queues().create(req);
                else shell.services().queues().update(q.id(), req);
            });
        });
    }

    private void act(Runnable r) {
        Wan.background(() -> {
            r.run();
            return true;
        }, ok -> reload(), e -> shell.toast("error", "Queue", e.getMessage()));
    }
}
