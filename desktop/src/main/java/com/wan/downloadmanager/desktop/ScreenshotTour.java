package com.wan.downloadmanager.desktop;

import javafx.animation.PauseTransition;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.WritableImage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Developer aid ({@code --screenshot-tour=<dir>}): visits every page, waits for its data to load and
 * writes a PNG of the window — used to review the UI and for the documentation screenshots.
 */
final class ScreenshotTour {
    private final MainShell shell;
    private final Path dir;

    ScreenshotTour(MainShell shell, Path dir) {
        this.shell = shell;
        this.dir = dir;
    }

    void run() {
        Deque<MainShell.Page> pages = new ArrayDeque<>(List.of(MainShell.Page.values()));
        next(pages);
    }

    private void next(Deque<MainShell.Page> pages) {
        if (pages.isEmpty()) {
            System.out.println("SCREENSHOT TOUR DONE " + dir);
            shell.exit();
            return;
        }
        MainShell.Page p = pages.poll();
        shell.go(p);
        PauseTransition wait = new PauseTransition(Duration.millis(1500));
        wait.setOnFinished(e -> {
            try {
                Files.createDirectories(dir);
                WritableImage img = shell.stage().getScene().snapshot(null);
                ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png",
                        dir.resolve(String.format("%02d-%s.png", p.ordinal() + 1, p.name().toLowerCase())).toFile());
            } catch (Exception ex) {
                System.err.println("screenshot failed for " + p + ": " + ex);
            }
            next(pages);
        });
        wait.play();
    }
}
