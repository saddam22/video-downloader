package com.wan.downloadmanager.desktop.ui;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * WAN component library for JavaFX — the native counterparts of the WAN web components
 * (see docs/WAN-DESIGN-SYSTEM-AUDIT.md). Styling lives in design/*.css; these factories only set
 * structure and style classes so every screen looks the same.
 */
public final class Wan {
    private Wan() {}

    // ------------------------------------------------------------------ WanButton
    public enum Variant { PRIMARY, SECONDARY, GHOST, DANGER }

    public static Button button(String text, Variant v, String icon, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().addAll("wan-button", v.name().toLowerCase(Locale.ROOT));
        if (icon != null) b.setGraphic(Icons.icon(icon, 16, v == Variant.PRIMARY ? Color.WHITE : Color.web("#6b7fa8")));
        if (action != null) b.setOnAction(e -> action.run());
        return b;
    }

    public static Button iconButton(String icon, String tooltip, Runnable action) {
        Button b = new Button();
        b.getStyleClass().add("icon-button");
        b.setGraphic(Icons.icon(icon, 16, Color.web("#6b7fa8")));
        b.setTooltip(new Tooltip(tooltip));
        b.setAccessibleText(tooltip);
        b.setOnAction(e -> action.run());
        return b;
    }

    // ------------------------------------------------------------------ WanCard
    public static VBox card(Node... children) {
        VBox v = new VBox(10, children);
        v.getStyleClass().add("wan-card");
        return v;
    }

    public static VBox statCard(String icon, String accent, String title, Label value, Label sub) {
        StackPane ic = new StackPane(Icons.icon(icon, 18, Color.web(accent)));
        ic.getStyleClass().add("stat-icon");
        ic.setStyle("-fx-background-color: " + accent + "22;");
        Label t = text(title, "muted");
        HBox top = new HBox(10, ic, t);
        top.setAlignment(Pos.CENTER_LEFT);
        value.getStyleClass().add("stat-value");
        sub.getStyleClass().addAll("muted", "small");
        VBox c = card(top, value, sub);
        c.getStyleClass().add("stat-card");
        HBox.setHgrow(c, Priority.ALWAYS);
        return c;
    }

    // ------------------------------------------------------------------ text + inputs
    public static Label text(String s, String... classes) {
        Label l = new Label(s);
        l.getStyleClass().addAll(classes);
        l.setWrapText(true);
        return l;
    }

    /** WanTextField: label + field + optional hint. */
    public static VBox field(String label, Node input, String hint) {
        Label l = text(label, "field-label");
        l.setLabelFor(input instanceof Control ? input : null);
        VBox v = new VBox(5, l, input);
        if (hint != null) v.getChildren().add(text(hint, "muted", "small"));
        return v;
    }

    public static TextField textField(String prompt) {
        TextField t = new TextField();
        t.setPromptText(prompt);
        return t;
    }

    /** WanDropdown */
    public static <T> ComboBox<T> dropdown(java.util.Collection<T> items, T selected) {
        ComboBox<T> c = new ComboBox<>();
        c.getItems().setAll(items);
        c.setValue(selected);
        c.setMaxWidth(Double.MAX_VALUE);
        return c;
    }

    public static Label chip(String text, String kind) {
        Label l = new Label(text);
        l.getStyleClass().addAll("chip", "chip-" + kind);
        return l;
    }

    // ------------------------------------------------------------------ WanToggle
    public static final class Toggle extends StackPane {
        private final BooleanProperty on = new SimpleBooleanProperty();

        public Toggle(boolean initial, Consumer<Boolean> onChange) {
            getStyleClass().add("wan-toggle");
            Region thumb = new Region();
            thumb.getStyleClass().add("thumb");
            thumb.setMaxSize(16, 16);
            getChildren().add(thumb);
            setMaxSize(40, 22);
            setMinSize(40, 22);
            setFocusTraversable(true);
            setAccessibleRole(javafx.scene.AccessibleRole.TOGGLE_BUTTON);
            on.addListener((o, a, b) -> {
                StackPane.setAlignment(thumb, b ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
                StackPane.setMargin(thumb, new Insets(0, 3, 0, 3));
                if (b) getStyleClass().add("on");
                else getStyleClass().remove("on");
                if (onChange != null) onChange.accept(b);
            });
            StackPane.setAlignment(thumb, Pos.CENTER_LEFT);
            StackPane.setMargin(thumb, new Insets(0, 3, 0, 3));
            on.set(initial);
            if (initial) getStyleClass().add("on");
            setOnMouseClicked(e -> on.set(!on.get()));
            setOnKeyPressed(e -> {
                if (e.getCode() == javafx.scene.input.KeyCode.SPACE || e.getCode() == javafx.scene.input.KeyCode.ENTER) on.set(!on.get());
            });
        }

        public boolean isOn() { return on.get(); }
        public void setOn(boolean v) { on.set(v); }
    }

    public static HBox toggleRow(String title, String hint, Toggle toggle) {
        VBox text = new VBox(2, text(title, "h3"));
        if (hint != null) text.getChildren().add(text(hint, "muted", "small"));
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(16, text, toggle);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // ------------------------------------------------------------------ WanProgressBar
    public static final class Progress extends StackPane {
        private final Region bar = new Region();
        private double value = -1;

        public Progress() {
            getStyleClass().add("wan-progress");
            Region track = new Region();
            track.getStyleClass().add("track");
            bar.getStyleClass().add("bar");
            setMinHeight(8);
            setPrefHeight(8);
            setMaxHeight(8);
            getChildren().addAll(track, bar);
            StackPane.setAlignment(bar, Pos.CENTER_LEFT);
            widthProperty().addListener((o, a, b) -> layoutBar());
        }

        /** 0..1, or negative for "unknown size" (bar hidden: no fake progress). */
        public void set(double v, String state) {
            value = v;
            getStyleClass().removeAll("done", "paused", "failed");
            if (state != null) getStyleClass().add(state);
            layoutBar();
        }

        private void layoutBar() {
            double w = getWidth();
            bar.setVisible(value >= 0);
            bar.setMaxWidth(Math.max(0, Math.min(1, value)) * w);
            bar.setPrefWidth(Math.max(0, Math.min(1, value)) * w);
        }
    }

    // ------------------------------------------------------------------ WanEmptyState
    public static VBox empty(String icon, String title, String body, Button action) {
        StackPane ic = new StackPane(Icons.icon(icon, 26, Color.web("#3b7bff")));
        ic.getStyleClass().add("empty-icon");
        VBox v = new VBox(8, ic, text(title, "h2"), text(body, "muted"));
        if (action != null) v.getChildren().add(action);
        v.getStyleClass().add("wan-empty");
        v.setAlignment(Pos.CENTER);
        return v;
    }

    public static HBox banner(String kind, String message, Node... actions) {
        Label l = text(message, "body");
        HBox.setHgrow(l, Priority.ALWAYS);
        l.setMaxWidth(Double.MAX_VALUE);
        HBox h = new HBox(10);
        h.getChildren().add(Icons.icon(kind.equals("err") ? Icons.INFO : Icons.SHIELD, 18, Color.web(kind.equals("err") ? "#c62828" : kind.equals("warn") ? "#b45309" : "#1d5bf5")));
        h.getChildren().add(l);
        h.getChildren().addAll(actions);
        h.getStyleClass().addAll("banner", kind);
        return h;
    }

    // ------------------------------------------------------------------ WanNotification (toasts)
    public static final class Toasts extends VBox {
        public Toasts() {
            super(10);
            setAlignment(Pos.BOTTOM_RIGHT);
            setPickOnBounds(false);
            setPadding(new Insets(20));
            setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        }

        public void show(String level, String title, String message) {
            Label t = text(title, "h3");
            Label m = text(message == null ? "" : message, "muted");
            VBox body = new VBox(2, t, m);
            HBox.setHgrow(body, Priority.ALWAYS);
            Button close = iconButton(Icons.PLUS, "Dismiss", () -> {});
            close.setRotate(45);
            HBox box = new HBox(10, body, close);
            box.getStyleClass().addAll("wan-notification", level);
            close.setOnAction(e -> getChildren().remove(box));
            getChildren().add(box);
            if (getChildren().size() > 4) getChildren().removeFirst();
            PauseTransition wait = new PauseTransition(Duration.seconds(6));
            wait.setOnFinished(e -> {
                FadeTransition f = new FadeTransition(Duration.millis(300), box);
                f.setToValue(0);
                f.setOnFinished(x -> getChildren().remove(box));
                f.play();
            });
            wait.play();
        }
    }

    // ------------------------------------------------------------------ WanDialog
    public static <R> Dialog<R> dialog(javafx.stage.Window owner, String title, Node content, ButtonType... buttons) {
        Dialog<R> d = new Dialog<>();
        d.initOwner(owner);
        d.setTitle(title);
        DialogPane p = d.getDialogPane();
        p.getStylesheets().setAll(owner.getScene().getStylesheets());
        p.getStyleClass().add("wan-dialog");
        VBox wrap = new VBox(14, text(title, "h2"), content);
        wrap.setPadding(new Insets(6, 4, 4, 4));
        p.setContent(wrap);
        p.getButtonTypes().setAll(buttons);
        p.setPrefWidth(520);
        for (ButtonType bt : buttons) {
            Button b = (Button) p.lookupButton(bt);
            b.getStyleClass().addAll("wan-button", bt.getButtonData().isDefaultButton() ? "primary" : "secondary");
        }
        return d;
    }

    public static boolean confirm(javafx.stage.Window owner, String title, String message, String confirmText) {
        ButtonType ok = new ButtonType(confirmText, ButtonBar.ButtonData.OK_DONE);
        Dialog<ButtonType> d = dialog(owner, title, text(message, "body"), ok, ButtonType.CANCEL);
        return d.showAndWait().filter(b -> b == ok).isPresent();
    }

    // ------------------------------------------------------------------ threading
    /** Runs work off the FX thread; delivers the result (or error) back on it. Never blocks the UI. */
    public static <T> void background(Supplier<T> work, Consumer<T> onDone, Consumer<Throwable> onError) {
        Thread.ofVirtual().name("wdm-ui-task").start(() -> {
            try {
                T r = work.get();
                Platform.runLater(() -> onDone.accept(r));
            } catch (Throwable t) {
                Platform.runLater(() -> onError.accept(t));
            }
        });
    }

    public static String bytes(Long b) {
        if (b == null) return "—";
        if (b < 1024) return b + " B";
        if (b < 1048576) return String.format(Locale.ROOT, "%.0f KB", b / 1024.0);
        if (b < 1073741824L) return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", b / 1073741824.0);
    }

    public static String speed(long bps) {
        return bps <= 0 ? "—" : bytes(bps) + "/s";
    }

    public static String eta(Long secs) {
        if (secs == null) return "—";
        if (secs < 60) return secs + " s";
        if (secs < 3600) return (secs / 60) + " min " + (secs % 60) + " s";
        return (secs / 3600) + " h " + (secs % 3600 / 60) + " min";
    }

    public static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }
}
