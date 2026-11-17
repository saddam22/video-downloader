package com.wan.downloadmanager.desktop.views;

import com.wan.downloadmanager.desktop.MainShell;
import com.wan.downloadmanager.desktop.ui.Icons;
import com.wan.downloadmanager.desktop.ui.Wan;
import com.wan.downloadmanager.notification.EventBus;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Notification Center: everything shown as a toast during this session. */
public class NotificationsView implements MainShell.View {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private final MainShell shell;

    public NotificationsView(MainShell shell) {
        this.shell = shell;
    }

    @Override
    public Node build() {
        VBox list = new VBox(8);
        List<EventBus.Notice> items = List.copyOf(shell.notices());
        if (items.isEmpty()) list.getChildren().add(Wan.empty(Icons.BELL, "No notifications", "Completed and failed downloads are announced here.", null));
        for (EventBus.Notice n : items) {
            HBox row = new HBox(12, Wan.chip(n.level(), switch (n.level()) { case "success" -> "ok"; case "error" -> "err"; default -> "info"; }),
                    new VBox(2, Wan.text(n.title(), "h3"), Wan.text(n.message() == null ? "" : n.message(), "muted")), Wan.spacer(),
                    Wan.text(FMT.format(Instant.ofEpochMilli(n.at())), "muted", "small"));
            row.setAlignment(Pos.CENTER_LEFT);
            list.getChildren().add(Wan.card(row));
        }
        VBox page = new VBox(14, new HBox(Wan.spacer(), Wan.button("Clear", Wan.Variant.SECONDARY, Icons.TRASH, () -> {
            shell.notices().clear();
            shell.go(MainShell.Page.NOTIFICATIONS);
        })), list);
        page.getStyleClass().add("page");
        return page;
    }
}
