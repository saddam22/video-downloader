package com.wan.downloadmanager.desktop.views;

import com.wan.downloadmanager.desktop.MainShell;
import com.wan.downloadmanager.desktop.ui.Icons;
import com.wan.downloadmanager.desktop.ui.Wan;
import com.wan.downloadmanager.entitlement.Feature;
import com.wan.downloadmanager.entity.Schedule;
import com.wan.downloadmanager.queue.QueueService;
import com.wan.downloadmanager.scheduler.SchedulerService;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.List;

/** Download windows that start (and optionally stop) a queue, with an optional bandwidth cap. */
public class SchedulerView implements MainShell.View {
    private static final List<String> DAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    private final MainShell shell;
    private final VBox list = new VBox(10);

    public SchedulerView(MainShell shell) {
        this.shell = shell;
    }

    @Override
    public Node build() {
        var s = shell.services();
        VBox page = new VBox(16);
        page.getStyleClass().add("page");
        Button add = Wan.button("New schedule", Wan.Variant.PRIMARY, Icons.PLUS, () -> edit(null));
        if (!s.entitlements().has(Feature.DOWNLOAD_SCHEDULER)) {
            add.setDisable(true);
            page.getChildren().add(Wan.banner("warn", "The scheduler is part of the Pro and Business plans.",
                    Wan.button("See plans", Wan.Variant.PRIMARY, null, () -> shell.go(MainShell.Page.ACCOUNT))));
        }
        page.getChildren().addAll(new HBox(10, add, Wan.text("Times are local to this computer. The app must be running (it can sit in the tray).", "muted")), list);
        reload();
        return page;
    }

    private void reload() {
        Wan.background(() -> shell.services().scheduler().list(), items -> {
            list.getChildren().clear();
            if (items.isEmpty()) list.getChildren().add(Wan.empty(Icons.SCHEDULE, "No schedules", "Start a queue at night or on weekends.", null));
            for (Schedule sc : items) {
                String window = sc.getStartTime() + (sc.getEndTime() != null ? "–" + sc.getEndTime() : " (start only)");
                String when = sc.getOneShotDate() != null ? "on " + sc.getOneShotDate() : sc.getDaysOfWeek().replace(",", " ");
                HBox row = new HBox(12, Wan.text(sc.getName(), "h3"), Wan.chip(sc.isEnabled() ? "On" : "Off", sc.isEnabled() ? "ok" : "free"),
                        Wan.text(window + " · " + when + (sc.getSpeedLimitBps() > 0 ? " · limit " + Wan.speed(sc.getSpeedLimitBps()) : ""), "muted"),
                        Wan.spacer(), Wan.button("Edit", Wan.Variant.GHOST, Icons.SETTINGS, () -> edit(sc)),
                        Wan.iconButton(Icons.TRASH, "Delete", () -> act(() -> shell.services().scheduler().delete(sc.getId()))));
                row.setAlignment(Pos.CENTER_LEFT);
                list.getChildren().add(Wan.card(row));
            }
        }, e -> shell.toast("error", "Scheduler", e.getMessage()));
    }

    private void edit(Schedule sc) {
        TextField name = Wan.textField("Night queue");
        TextField start = Wan.textField("23:00");
        TextField end = Wan.textField("06:00 (optional)");
        ComboBox<QueueService.QueueView> queue = Wan.dropdown(shell.services().queues().list(), null);
        queue.getSelectionModel().selectFirst();
        queue.setConverter(new javafx.util.StringConverter<>() {
            public String toString(QueueService.QueueView q) { return q == null ? "" : q.name(); }
            public QueueService.QueueView fromString(String x) { return null; }
        });
        HBox days = new HBox(6);
        List<CheckBox> boxes = new ArrayList<>();
        for (String d : DAYS) {
            CheckBox cb = new CheckBox(d.charAt(0) + d.substring(1).toLowerCase());
            cb.setUserData(d);
            cb.setSelected(sc == null || sc.getDaysOfWeek().contains(d));
            boxes.add(cb);
            days.getChildren().add(cb);
        }
        TextField limit = Wan.textField("0 = no limit");
        CheckBox enabled = new CheckBox("Enabled");
        enabled.setSelected(true);
        if (sc != null) {
            name.setText(sc.getName());
            start.setText(sc.getStartTime());
            if (sc.getEndTime() != null) end.setText(sc.getEndTime());
            limit.setText(String.valueOf(sc.getSpeedLimitBps() / 1024));
            enabled.setSelected(sc.isEnabled());
            queue.getItems().stream().filter(q -> q.id().equals(sc.getQueueId())).findFirst().ifPresent(queue::setValue);
        }
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        var d = Wan.dialog(shell.stage(), sc == null ? "New schedule" : "Edit schedule", new VBox(12,
                Wan.field("Name", name, null), Wan.field("Queue", queue, null),
                new HBox(12, Wan.field("Start (HH:mm)", start, null), Wan.field("End (HH:mm)", end, "Leave empty to only start")),
                Wan.field("Days", days, null), Wan.field("Speed limit while active (KB/s)", limit, null), enabled), save, ButtonType.CANCEL);
        d.showAndWait().filter(b -> b == save).ifPresent(b -> {
            long kb;
            try {
                kb = limit.getText().isBlank() ? 0 : Long.parseLong(limit.getText().trim());
            } catch (NumberFormatException e) {
                shell.toast("error", "Schedule", "Speed limit must be a number");
                return;
            }
            var req = new SchedulerService.ScheduleRequest(name.getText(), queue.getValue() == null ? null : queue.getValue().id(),
                    start.getText().trim(), end.getText().isBlank() || end.getText().contains("optional") ? null : end.getText().trim(),
                    boxes.stream().filter(CheckBox::isSelected).map(x -> (String) x.getUserData()).toList(), null, kb * 1024, enabled.isSelected());
            act(() -> shell.services().scheduler().save(sc == null ? null : sc.getId(), req));
        });
    }

    private void act(Runnable r) {
        Wan.background(() -> {
            r.run();
            return true;
        }, ok -> reload(), e -> shell.toast("error", "Scheduler", e.getMessage()));
    }
}
