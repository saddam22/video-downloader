package com.wan.downloadmanager.desktop.views;

import com.wan.downloadmanager.desktop.MainShell;
import com.wan.downloadmanager.desktop.ui.Icons;
import com.wan.downloadmanager.desktop.ui.Wan;
import com.wan.downloadmanager.entitlement.Feature;
import com.wan.downloadmanager.media.MediaService;
import com.wan.downloadmanager.notification.EventBus;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;

import java.util.List;
import java.util.Locale;

/** Video Grabber: media detected by the browser extensions, with formats verified from the source. */
public class VideoGrabberView implements MainShell.View {
    private final MainShell shell;
    private final VBox list = new VBox(12);
    private Runnable unsub;

    public VideoGrabberView(MainShell shell) {
        this.shell = shell;
    }

    @Override
    public Node build() {
        var s = shell.services();
        VBox page = new VBox(16);
        page.getStyleClass().add("page");
        if (!s.entitlements().has(Feature.VIDEO_DETECTION)) {
            page.getChildren().add(Wan.banner("warn", "Video detection is part of the Pro and Business plans. Your current plan: "
                    + s.entitlements().state().tier() + ".", Wan.button("See plans", Wan.Variant.PRIMARY, null, () -> shell.go(MainShell.Page.ACCOUNT))));
        }
        page.getChildren().add(Wan.banner("info", "Open a page with a video in Chrome, Edge or Firefox; the WAN extension reports it here. "
                + "Only formats confirmed by the source are listed. Protected (DRM) or encrypted streams are never downloaded.",
                Wan.button("Clear list", Wan.Variant.SECONDARY, Icons.TRASH, () -> Wan.background(() -> {
                    s.media().clear();
                    return true;
                }, ok -> reload(), e -> shell.toast("error", "Video Grabber", e.getMessage())))));
        page.getChildren().add(list);
        unsub = s.bus().subscribe(EventBus.MEDIA, p -> Platform.runLater(this::reload));
        reload();
        return page;
    }

    private void reload() {
        Wan.background(() -> shell.services().media().list(), this::render, e -> shell.toast("error", "Video Grabber", e.getMessage()));
    }

    private void render(List<MediaService.SourceView> sources) {
        list.getChildren().clear();
        if (sources.isEmpty()) {
            list.getChildren().add(Wan.empty(Icons.VIDEO, "No videos detected yet", "Install the WAN browser extension and play a video on a page.",
                    Wan.button("Browser integration", Wan.Variant.PRIMARY, Icons.BROWSER, () -> shell.go(MainShell.Page.BROWSER))));
            return;
        }
        for (MediaService.SourceView v : sources) list.getChildren().add(card(v));
    }

    private Node card(MediaService.SourceView v) {
        StackPane thumb = new StackPane();
        thumb.setMinSize(160, 90);
        thumb.setMaxSize(160, 90);
        thumb.setStyle("-fx-background-color: linear-gradient(to bottom right, #18296e, #6d28d9); -fx-background-radius: 10;");
        if (v.thumbnail() != null) {
            ImageView iv = new ImageView(new Image(v.thumbnail(), 160, 90, true, true, true));
            thumb.getChildren().add(iv);
        } else thumb.getChildren().add(com.wan.downloadmanager.desktop.ui.Icons.icon(Icons.VIDEO, 28, javafx.scene.paint.Color.WHITE));
        if (v.durationSec() != null) {
            Label dur = new Label(duration(v.durationSec()));
            dur.setStyle("-fx-background-color: rgba(5,13,43,.8); -fx-text-fill: white; -fx-padding: 1 5; -fx-background-radius: 5; -fx-font-size: 11px;");
            StackPane.setAlignment(dur, Pos.BOTTOM_RIGHT);
            thumb.getChildren().add(dur);
        }
        VBox info = new VBox(4, Wan.text(v.title() == null ? "Untitled video" : v.title(), "h3"),
                Wan.text(v.kind() + " · " + DownloadsView.hostOf(v.pageUrl()), "muted", "small"));
        if (v.note() != null) info.getChildren().add(Wan.chip(v.note(), v.protectedContent() ? "err" : "warn"));
        VBox formats = new VBox(6);
        for (MediaService.FormatView f : v.formats()) {
            HBox row = new HBox(10, Wan.text(f.label(), "body"),
                    Wan.text(codecs(f), "muted", "small"), Wan.spacer(),
                    Wan.button("Download", Wan.Variant.PRIMARY, Icons.DOWNLOAD, () -> Wan.background(
                            () -> shell.services().media().download(v.id(), f.id()),
                            d -> shell.toast("success", "Download added", d.fileName()),
                            e -> shell.toast("error", "Cannot download", e.getMessage()))));
            row.setAlignment(Pos.CENTER_LEFT);
            formats.getChildren().add(row);
        }
        info.getChildren().add(formats);
        HBox.setHgrow(info, Priority.ALWAYS);
        HBox body = new HBox(16, thumb, info);
        return Wan.card(body);
    }

    private static String codecs(MediaService.FormatView f) {
        String v = f.videoCodec() == null ? "" : f.videoCodec();
        String a = f.audioCodec() == null ? "" : f.audioCodec();
        return (v + (v.isEmpty() || a.isEmpty() ? "" : " / ") + a).toLowerCase(Locale.ROOT);
    }

    static String duration(double secs) {
        long s = Math.round(secs);
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
    }

    @Override
    public void dispose() {
        if (unsub != null) unsub.run();
    }
}
