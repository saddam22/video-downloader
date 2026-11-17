package com.wan.downloadmanager.desktop.ui;

import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.SVGPath;

/** Original line icons (24×24 paths, stroked) used across the app. */
public final class Icons {
    private Icons() {}

    public static final String DASHBOARD = "M3 13h8V3H3zm0 8h8v-6H3zm10 0h8V11h-8zm0-18v6h8V3z";
    public static final String DOWNLOAD = "M12 3v12m0 0l-5-5m5 5l5-5M4 19h16";
    public static final String ACTIVE = "M13 2L3 14h8l-1 8 10-12h-8l1-8z";
    public static final String DONE = "M5 13l4 4L19 7";
    public static final String QUEUE = "M4 6h16M4 12h16M4 18h10";
    public static final String VIDEO = "M4 6h11a1 1 0 011 1v10a1 1 0 01-1 1H4a1 1 0 01-1-1V7a1 1 0 011-1zm12 4l5-3v10l-5-3";
    public static final String AUDIO = "M9 18V5l12-2v13M9 18a3 3 0 11-6 0 3 3 0 016 0zm12-2a3 3 0 11-6 0 3 3 0 016 0z";
    public static final String CONVERT = "M4 7h13l-3-3M20 17H7l3 3";
    public static final String BATCH = "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01";
    public static final String SCHEDULE = "M12 7v5l3 3M12 21a9 9 0 100-18 9 9 0 000 18z";
    public static final String BROWSER = "M3 12a9 9 0 1018 0 9 9 0 00-18 0zm0 0h18M12 3c2.5 2.5 3.5 5.5 3.5 9s-1 6.5-3.5 9c-2.5-2.5-3.5-5.5-3.5-9s1-6.5 3.5-9z";
    public static final String HISTORY = "M3 12a9 9 0 109-9 9.7 9.7 0 00-6.7 2.7L3 8m0-5v5h5m4-1v5l4 2";
    public static final String SETTINGS = "M12 15a3 3 0 100-6 3 3 0 000 6zm7.4-3a7.4 7.4 0 00-.1-1.3l2-1.6-2-3.4-2.4 1a7.3 7.3 0 00-2.2-1.3L14.4 3h-4l-.4 2.4a7.3 7.3 0 00-2.2 1.3l-2.4-1-2 3.4 2 1.6a7.4 7.4 0 000 2.6l-2 1.6 2 3.4 2.4-1a7.3 7.3 0 002.2 1.3l.4 2.4h4l.4-2.4a7.3 7.3 0 002.2-1.3l2.4 1 2-3.4-2-1.6c.1-.4.1-.9.1-1.3z";
    public static final String BELL = "M18 8a6 6 0 10-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 01-3.4 0";
    public static final String USER = "M20 21a8 8 0 10-16 0M12 11a4 4 0 100-8 4 4 0 000 8z";
    public static final String PAUSE = "M8 5v14M16 5v14";
    public static final String PLAY = "M7 4l13 8-13 8z";
    public static final String RETRY = "M4 4v6h6M20 20v-6h-6M5.5 15a7 7 0 0011.8 2.5L20 14M4 10l2.7-3.5A7 7 0 0118.5 9";
    public static final String STOP = "M6 6h12v12H6z";
    public static final String TRASH = "M4 7h16M10 11v6M14 11v6M5 7l1 13h12l1-13M9 7V4h6v3";
    public static final String FOLDER = "M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2z";
    public static final String PLUS = "M12 5v14M5 12h14";
    public static final String LINK = "M10 14a5 5 0 007 0l3-3a5 5 0 00-7-7l-1 1M14 10a5 5 0 00-7 0l-3 3a5 5 0 007 7l1-1";
    public static final String SEARCH = "M11 19a8 8 0 100-16 8 8 0 000 16zm10 2l-4.3-4.3";
    public static final String SPEED = "M12 14l4-4M3.3 17a10 10 0 1117.4 0";
    public static final String DISK = "M4 6a8 3 0 0016 0M4 6a8 3 0 0116 0M4 6v12a8 3 0 0016 0V6M4 12a8 3 0 0016 0";
    public static final String SHIELD = "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z";
    public static final String INFO = "M12 21a9 9 0 100-18 9 9 0 000 18zm0-12h.01M11 12h1v5h1";
    public static final String UP = "M12 19V5m-6 6l6-6 6 6";
    public static final String DOWN = "M12 5v14m6-6l-6 6-6-6";

    public static Node icon(String path, double size, Paint color) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.setFill(Color.TRANSPARENT);
        p.setStroke(color);
        p.setStrokeWidth(1.8);
        double scale = size / 24.0;
        p.setScaleX(scale);
        p.setScaleY(scale);
        javafx.scene.Group g = new javafx.scene.Group(p);
        javafx.scene.layout.StackPane box = new javafx.scene.layout.StackPane(g);
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        return box;
    }

    public static Node icon(String path, double size) {
        return icon(path, size, Color.web("#8fa6d8"));
    }
}
