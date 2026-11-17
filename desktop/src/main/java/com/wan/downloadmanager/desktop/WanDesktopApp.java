package com.wan.downloadmanager.desktop;

import com.wan.downloadmanager.WanDownloadManagerBackend;
import com.wan.downloadmanager.config.DataDirEnvironmentPostProcessor;
import com.wan.downloadmanager.desktop.ui.Wan;
import com.wan.downloadmanager.identity.BrowserLauncher;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Desktop entry point: single-instance check → splash → embedded backend (Spring Boot on 127.0.0.1)
 * → main window. Closing the window minimises to the tray when enabled; "Exit" stops downloads cleanly.
 */
public class WanDesktopApp extends Application {
    private static FileLock instanceLock;
    private ConfigurableApplicationContext ctx;
    private MainShell shell;

    @Override
    public void start(Stage stage) {
        Path dataDir = dataDir();
        if (!acquireInstanceLock(dataDir)) {
            Label l = Wan.text("WAN Download Manager is already running. Use the icon in the system tray.", "h3");
            Stage s = new Stage();
            s.setScene(new Scene(new StackPane(l), 460, 120));
            s.setTitle("WAN Download Manager");
            s.show();
            return;
        }
        Stage splash = splash();
        splash.show();
        List<String> args = new ArrayList<>(getParameters().getRaw());
        Wan.background(() -> startBackend(args), c -> {
            ctx = c;
            Services services = Services.from(c);
            shell = new MainShell(stage, services, getHostServices());
            shell.show();
            splash.close();
            String tour = getParameters().getNamed().get("screenshot-tour");
            if (tour != null) new ScreenshotTour(shell, Path.of(tour)).run();
        }, err -> {
            splash.close();
            Label l = Wan.text("WAN Download Manager could not start: " + err.getMessage(), "h3");
            Stage s = new Stage();
            s.setScene(new Scene(new StackPane(l), 560, 160));
            s.show();
        });
    }

    private ConfigurableApplicationContext startBackend(List<String> args) {
        BrowserLauncher systemBrowser = uri -> getHostServices().showDocument(uri.toString());
        return new SpringApplicationBuilder(WanDownloadManagerBackend.class)
                .headless(false)
                .initializers(c -> c.getBeanFactory().registerSingleton("browserLauncher", systemBrowser))
                .run(args.stream().filter(a -> a.startsWith("--wan.") || a.startsWith("--spring.")).toArray(String[]::new));
    }

    private Stage splash() {
        Stage s = new Stage(StageStyle.UNDECORATED);
        Label name = new Label("WAN Download Manager");
        name.setStyle("-fx-text-fill: white; -fx-font-size: 22px; -fx-font-weight: bold;");
        Label tag = new Label("Download Everything. Manage Smarter.");
        tag.setStyle("-fx-text-fill: #c4d4f5;");
        ProgressIndicator p = new ProgressIndicator();
        p.setMaxSize(28, 28);
        VBox box = new VBox(12, MainShell.logo(64), name, tag, p);
        box.setAlignment(Pos.CENTER);
        box.setStyle("-fx-background-color: linear-gradient(to bottom right, #0a1640, #18296e 60%, #3b1f8f); -fx-background-radius: 20;");
        Scene sc = new Scene(box, 420, 300);
        sc.setFill(javafx.scene.paint.Color.TRANSPARENT);
        s.setScene(sc);
        s.initStyle(StageStyle.TRANSPARENT);
        return s;
    }

    static Path dataDir() {
        String override = System.getProperty("wan.data-dir");
        return override != null ? Path.of(override) : DataDirEnvironmentPostProcessor.defaultDataDir();
    }

    /** One instance per user: an exclusive lock on a file in the data folder (released by the OS on exit/crash). */
    static boolean acquireInstanceLock(Path dir) {
        try {
            Files.createDirectories(dir);
            @SuppressWarnings("resource")
            RandomAccessFile raf = new RandomAccessFile(dir.resolve("instance.lock").toFile(), "rw");
            instanceLock = raf.getChannel().tryLock();
            return instanceLock != null;
        } catch (IOException e) {
            return true; // cannot lock (e.g. network drive): do not block the user
        }
    }

    @Override
    public void stop() {
        if (ctx != null) ctx.close(); // pauses running jobs with their offsets saved
        Platform.exit();
        System.exit(0);
    }

    static void openInBrowser(javafx.application.HostServices hs, URI uri) {
        hs.showDocument(uri.toString());
    }
}
