package com.wan.downloadmanager.desktop;

import javafx.application.Application;

/** Plain main class (JavaFX on the class path needs a launcher that does not extend Application). */
public final class DesktopLauncher {
    private DesktopLauncher() {}

    public static void main(String[] args) {
        Application.launch(WanDesktopApp.class, args);
    }
}
