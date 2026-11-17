package com.wan.downloadmanager.desktop;

import com.wan.downloadmanager.desktop.ui.Icons;
import com.wan.downloadmanager.desktop.ui.Wan;
import com.wan.downloadmanager.desktop.views.*;
import com.wan.downloadmanager.entitlement.EntitlementService;
import com.wan.downloadmanager.identity.IdentityService;
import com.wan.downloadmanager.notification.EventBus;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;

import java.util.*;
import java.util.function.Supplier;

/** Main window: WAN sidebar + header + routed content + toasts. */
public class MainShell {
    public enum Page {
        DASHBOARD("Dashboard", Icons.DASHBOARD), ALL("All Downloads", Icons.DOWNLOAD), ACTIVE("Active", Icons.ACTIVE),
        COMPLETED("Completed", Icons.DONE), QUEUE("Queue", Icons.QUEUE), GRABBER("Video Grabber", Icons.VIDEO),
        AUDIO("Audio", Icons.AUDIO), CONVERTER("Media Converter", Icons.CONVERT), BATCH("Batch Download", Icons.BATCH),
        SCHEDULER("Scheduler", Icons.SCHEDULE), BROWSER("Browser Integration", Icons.BROWSER), HISTORY("History", Icons.HISTORY),
        SETTINGS("Settings", Icons.SETTINGS), ACCOUNT("Account", Icons.USER), NOTIFICATIONS("Notifications", Icons.BELL),
        ABOUT("About & Diagnostics", Icons.INFO);

        public final String title;
        public final String icon;

        Page(String title, String icon) {
            this.title = title;
            this.icon = icon;
        }
    }

    private final Stage stage;
    private final Services s;
    private final HostServices host;
    private final BorderPane root = new BorderPane();
    private final StackPane content = new StackPane();
    private final Wan.Toasts toasts = new Wan.Toasts();
    private final Map<Page, ToggleButton> nav = new EnumMap<>(Page.class);
    private final ToggleGroup navGroup = new ToggleGroup();
    private final Label pageTitle = Wan.text("", "h1");
    private final Label tierChip = new Label();
    private final Label avatar = new Label();
    private final Label bellBadge = new Label();
    private final List<EventBus.Notice> notices = Collections.synchronizedList(new ArrayList<>());
    private final List<Runnable> unsubscribe = new ArrayList<>();
    private View current;
    private Page currentPage;
    private Tray tray;

    public MainShell(Stage stage, Services s, HostServices host) {
        this.stage = stage;
        this.s = s;
        this.host = host;
    }

    public Services services() { return s; }
    public Stage stage() { return stage; }
    public HostServices host() { return host; }
    public List<EventBus.Notice> notices() { return notices; }

    public void show() {
        root.setLeft(sidebar());
        VBox main = new VBox(header(), content);
        VBox.setVgrow(content, Priority.ALWAYS);
        StackPane layered = new StackPane(main, toasts);
        StackPane.setAlignment(toasts, Pos.BOTTOM_RIGHT);
        root.setCenter(layered);
        Scene scene = new Scene(root, 1280, 800);
        applyTheme(scene, s.settings().get().theme());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN), this::addDownload);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN), this::pasteUrl);
        stage.setScene(scene);
        stage.setTitle("WAN Download Manager");
        stage.setMinWidth(1024);
        stage.setMinHeight(680);
        stage.getIcons().add(logo(64).snapshot(snapParams(), null));
        s.settings().onChange(st -> Platform.runLater(() -> applyTheme(scene, st.theme())));
        unsubscribe.add(s.bus().subscribe(EventBus.NOTIFICATIONS, n -> Platform.runLater(() -> onNotice(n))));
        s.entitlements().onChange(st -> Platform.runLater(this::refreshAccountChrome));
        refreshAccountChrome();
        tray = Tray.install(this);
        stage.setOnCloseRequest(e -> {
            if (tray != null && s.settings().get().minimizeToTray()) {
                e.consume();
                stage.hide();
                tray.info("WAN Download Manager is still running", "Downloads continue in the background.");
            } else {
                Platform.exit();
            }
        });
        go(Page.DASHBOARD);
        if (!s.settings().get().startMinimized()) stage.show();
    }

    public static void applyTheme(Scene scene, String theme) {
        boolean dark = switch (theme == null ? "SYSTEM" : theme) {
            case "LIGHT" -> false;
            case "DARK" -> true;
            default -> systemPrefersDark();
        };
        List<String> sheets = new ArrayList<>();
        for (String f : List.of("colors", "typography", "spacing", "components")) {
            sheets.add(Objects.requireNonNull(MainShell.class.getResource("/design/" + f + ".css")).toExternalForm());
        }
        sheets.add(Objects.requireNonNull(MainShell.class.getResource("/design/" + (dark ? "dark" : "light") + "-theme.css")).toExternalForm());
        scene.getStylesheets().setAll(sheets);
    }

    /** JavaFX 21 has no platform colour-scheme API; on Windows read AppsUseLightTheme, default dark. */
    static boolean systemPrefersDark() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return true;
        try {
            Process p = new ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            return !out.contains("0x1");
        } catch (Exception e) {
            return true;
        }
    }

    // ------------------------------------------------------------------ chrome

    private Node sidebar() {
        VBox side = new VBox();
        side.getStyleClass().add("wan-sidebar");
        HBox brand = new HBox(10, logo(34), new VBox(0, brandLabel("WAN"), brandLabel("Download Manager")));
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setStyle("-fx-padding: 0 4 10 4;");
        side.getChildren().add(brand);
        section(side, "OVERVIEW", Page.DASHBOARD);
        section(side, "DOWNLOADS", Page.ALL, Page.ACTIVE, Page.COMPLETED, Page.QUEUE);
        section(side, "MEDIA", Page.GRABBER, Page.AUDIO, Page.CONVERTER);
        section(side, "TOOLS", Page.BATCH, Page.SCHEDULER, Page.BROWSER, Page.HISTORY, Page.SETTINGS);
        side.getChildren().add(Wan.spacer());
        side.getChildren().add(diskCard());
        ScrollPane sp = new ScrollPane(side);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setStyle("-fx-background-color: #050d2b;");
        return sp;
    }

    private static Label brandLabel(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("brand-name");
        l.setWrapText(false);
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    private void section(VBox side, String title, Page... pages) {
        side.getChildren().add(Wan.text(title, "nav-section"));
        for (Page p : pages) {
            ToggleButton b = new ToggleButton(p.title, Icons.icon(p.icon, 17));
            b.getStyleClass().add("nav-item");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setToggleGroup(navGroup);
            b.setOnAction(e -> go(p));
            nav.put(p, b);
            side.getChildren().add(b);
        }
    }

    private Node diskCard() {
        Label title = Wan.text("Disk space", "h3");
        title.setStyle("-fx-text-fill: white;");
        Label free = Wan.text("", "small");
        ProgressBar bar = new ProgressBar(0);
        bar.setMaxWidth(Double.MAX_VALUE);
        Button clean = Wan.button("Open folder", Wan.Variant.SECONDARY, Icons.FOLDER, () -> openFolder(s.settings().get().downloadDir()));
        clean.setMaxWidth(Double.MAX_VALUE);
        VBox card = new VBox(6, title, bar, free, clean);
        card.getStyleClass().add("disk-card");
        Runnable update = () -> Wan.background(() -> s.stats().dashboard(), d -> {
            if (d.diskTotalBytes() != null && d.diskTotalBytes() > 0) {
                double used = 1 - (double) d.diskFreeBytes() / d.diskTotalBytes();
                bar.setProgress(used);
                free.setText(Wan.bytes(d.diskFreeBytes()) + " free of " + Wan.bytes(d.diskTotalBytes()));
            } else free.setText("Download folder not available");
        }, e -> free.setText("Disk information unavailable"));
        update.run();
        javafx.animation.Timeline t = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(30), e -> update.run()));
        t.setCycleCount(javafx.animation.Animation.INDEFINITE);
        t.play();
        return card;
    }

    private Node header() {
        TextField search = Wan.textField("Search downloads…");
        search.getStyleClass().add("search-field");
        search.setOnAction(e -> {
            go(Page.ALL);
            if (current instanceof DownloadsView dv) dv.search(search.getText());
        });
        Button add = Wan.button("Add URL", Wan.Variant.PRIMARY, Icons.PLUS, this::addDownload);
        Button bell = Wan.iconButton(Icons.BELL, "Notifications", () -> go(Page.NOTIFICATIONS));
        bellBadge.getStyleClass().add("badge");
        bellBadge.setVisible(false);
        StackPane bellWrap = new StackPane(bell, bellBadge);
        StackPane.setAlignment(bellBadge, Pos.TOP_RIGHT);
        Button settings = Wan.iconButton(Icons.SETTINGS, "Settings", () -> go(Page.SETTINGS));
        avatar.getStyleClass().add("avatar");
        avatar.setOnMouseClicked(e -> go(Page.ACCOUNT));
        avatar.setCursor(javafx.scene.Cursor.HAND);
        tierChip.setOnMouseClicked(e -> go(Page.ACCOUNT));
        tierChip.setCursor(javafx.scene.Cursor.HAND);
        HBox h = new HBox(12, pageTitle, Wan.spacer(), search, add, tierChip, bellWrap, settings, avatar);
        h.getStyleClass().add("wan-header");
        return h;
    }

    void refreshAccountChrome() {
        EntitlementService.State st = s.entitlements().state();
        tierChip.setText(st.tier().name().charAt(0) + st.tier().name().substring(1).toLowerCase(Locale.ROOT)
                + (st.source() == EntitlementService.Source.OFFLINE_CACHE ? " · offline" : ""));
        tierChip.getStyleClass().setAll("chip", "chip-" + st.tier().name().toLowerCase(Locale.ROOT));
        IdentityService.AccountView a = s.identity().account();
        String initials = "?";
        if (a.profile() != null && a.profile().name() != null && !a.profile().name().isBlank()) {
            String[] parts = a.profile().name().trim().split("\\s+");
            initials = (parts[0].substring(0, 1) + (parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "")).toUpperCase(Locale.ROOT);
        }
        avatar.setText(initials);
        Tooltip.install(avatar, new Tooltip(a.profile() == null ? "Not signed in — open Account" : a.profile().name()));
    }

    private void onNotice(Object n) {
        if (n instanceof EventBus.Notice notice) {
            notices.addFirst(notice);
            if (notices.size() > 200) notices.removeLast();
            bellBadge.setText(String.valueOf(Math.min(99, notices.size())));
            bellBadge.setVisible(true);
            if (s.settings().get().notifications()) {
                toasts.show(notice.level(), notice.title(), notice.message());
                if (!stage.isShowing() && tray != null) tray.info(notice.title(), notice.message());
            }
        } else if (n instanceof Map<?, ?> m && "account".equals(m.get("type"))) {
            refreshAccountChrome();
            if (current instanceof AccountView av) av.refresh();
        }
    }

    public void toast(String level, String title, String message) {
        toasts.show(level, title, message);
    }

    // ------------------------------------------------------------------ navigation

    public void go(Page p) {
        if (current != null) current.dispose();
        Supplier<View> factory = switch (p) {
            case DASHBOARD -> () -> new DashboardView(this);
            case ALL -> () -> new DownloadsView(this, "all");
            case ACTIVE -> () -> new DownloadsView(this, "active");
            case COMPLETED -> () -> new DownloadsView(this, "completed");
            case QUEUE -> () -> new QueueView(this);
            case GRABBER -> () -> new VideoGrabberView(this);
            case AUDIO -> () -> new ConverterView(this, true);
            case CONVERTER -> () -> new ConverterView(this, false);
            case BATCH -> () -> new BatchView(this);
            case SCHEDULER -> () -> new SchedulerView(this);
            case BROWSER -> () -> new BrowserView(this);
            case HISTORY -> () -> new HistoryView(this);
            case SETTINGS -> () -> new SettingsView(this);
            case ACCOUNT -> () -> new AccountView(this);
            case NOTIFICATIONS -> () -> new NotificationsView(this);
            case ABOUT -> () -> new AboutView(this);
        };
        current = factory.get();
        currentPage = p;
        pageTitle.setText(p.title);
        ToggleButton b = nav.get(p);
        if (b != null) b.setSelected(true);
        else navGroup.selectToggle(null);
        if (p == Page.NOTIFICATIONS) bellBadge.setVisible(false);
        ScrollPane sp = new ScrollPane(current.build());
        sp.setFitToWidth(true);
        sp.setFitToHeight(current.fillHeight());
        content.getChildren().setAll(sp);
    }

    public Page currentPage() {
        return currentPage;
    }

    public void addDownload() {
        new AddDownloadDialog(this, null).open();
    }

    public void pasteUrl() {
        String clip = javafx.scene.input.Clipboard.getSystemClipboard().getString();
        new AddDownloadDialog(this, clip != null && clip.trim().matches("(?i)https?://\\S+") ? clip.trim() : null).open();
    }

    public void openFolder(String path) {
        try {
            java.nio.file.Path p = java.nio.file.Path.of(path);
            java.nio.file.Files.createDirectories(p);
            host.showDocument(p.toUri().toString());
        } catch (Exception e) {
            toast("error", "Cannot open folder", e.getMessage());
        }
    }

    public void exit() {
        Platform.exit();
    }

    // ------------------------------------------------------------------ branding

    /** WAN Download Manager mark: the WAN blue roundel (corporate shape) with a download arrow. */
    public static StackPane logo(double size) {
        Circle c = new Circle(size / 2, new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#3b82f6")), new Stop(0.55, Color.web("#1e3a8a")), new Stop(1, Color.web("#6d28d9"))));
        Circle ring = new Circle(size / 2 * 0.85, Color.TRANSPARENT);
        ring.setStroke(Color.color(1, 1, 1, 0.35));
        ring.setStrokeWidth(Math.max(1, size / 50));
        SVGPath arrow = new SVGPath();
        arrow.setContent("M12 4v11m0 0l-5-5m5 5l5-5M5 20h14");
        arrow.setStroke(Color.WHITE);
        arrow.setFill(Color.TRANSPARENT);
        arrow.setStrokeWidth(2.4);
        arrow.setScaleX(size / 40);
        arrow.setScaleY(size / 40);
        StackPane p = new StackPane(c, ring, new javafx.scene.Group(arrow));
        p.setMinSize(size, size);
        p.setMaxSize(size, size);
        return p;
    }

    private static javafx.scene.SnapshotParameters snapParams() {
        javafx.scene.SnapshotParameters sp = new javafx.scene.SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);
        return sp;
    }

    /** A screen: built once, disposed when navigating away. */
    public interface View {
        Node build();

        default boolean fillHeight() { return false; }

        default void dispose() {}
    }
}
