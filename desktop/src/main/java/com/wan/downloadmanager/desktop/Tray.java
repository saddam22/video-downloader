package com.wan.downloadmanager.desktop;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.SnapshotParameters;
import javafx.scene.paint.Color;

import java.awt.*;
import java.awt.image.BufferedImage;

/** System tray icon (Windows notification area): open, pause/resume all, exit; desktop notifications. */
final class Tray {
    private final TrayIcon icon;

    private Tray(TrayIcon icon) {
        this.icon = icon;
    }

    static Tray install(MainShell shell) {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return null;
        try {
            SnapshotParameters sp = new SnapshotParameters();
            sp.setFill(Color.TRANSPARENT);
            BufferedImage img = SwingFXUtils.fromFXImage(MainShell.logo(32).snapshot(sp, null), null);
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Open WAN Download Manager");
            open.addActionListener(e -> Platform.runLater(() -> {
                shell.stage().show();
                shell.stage().toFront();
            }));
            MenuItem pause = new MenuItem("Pause all");
            pause.addActionListener(e -> Thread.ofVirtual().start(() -> shell.services().downloads().pauseAll()));
            MenuItem resume = new MenuItem("Resume all");
            resume.addActionListener(e -> Thread.ofVirtual().start(() -> shell.services().downloads().resumeAll()));
            MenuItem exit = new MenuItem("Exit");
            exit.addActionListener(e -> Platform.runLater(shell::exit));
            menu.add(open);
            menu.addSeparator();
            menu.add(pause);
            menu.add(resume);
            menu.addSeparator();
            menu.add(exit);
            TrayIcon ti = new TrayIcon(img, "WAN Download Manager", menu);
            ti.setImageAutoSize(true);
            ti.addActionListener(e -> Platform.runLater(() -> {
                shell.stage().show();
                shell.stage().toFront();
            }));
            SystemTray.getSystemTray().add(ti);
            return new Tray(ti);
        } catch (Exception e) {
            return null;
        }
    }

    void info(String title, String message) {
        icon.displayMessage(title, message == null ? "" : message, TrayIcon.MessageType.INFO);
    }
}
