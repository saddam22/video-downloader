package com.wan.downloadmanager.desktop.views;

import com.wan.downloadmanager.desktop.MainShell;
import com.wan.downloadmanager.desktop.ui.Icons;
import com.wan.downloadmanager.desktop.ui.Wan;
import com.wan.downloadmanager.entitlement.Feature;
import com.wan.downloadmanager.entity.ConversionJob;
import com.wan.downloadmanager.media.ConversionService;
import com.wan.downloadmanager.notification.EventBus;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Media Converter and Audio Downloader (audio extraction / conversion) — FFmpeg, one job at a time, cancellable. */
public class ConverterView implements MainShell.View {
    private final MainShell shell;
    private final boolean audio;
    private final VBox jobs = new VBox(8);
    private final List<File> files = new java.util.ArrayList<>();
    private Runnable unsub;

    public ConverterView(MainShell shell, boolean audio) {
        this.shell = shell;
        this.audio = audio;
    }

    @Override
    public Node build() {
        var s = shell.services();
        VBox page = new VBox(16);
        page.getStyleClass().add("page");
        if (!s.entitlements().has(Feature.MEDIA_CONVERSION)) {
            page.getChildren().add(Wan.banner("warn", "Conversion is part of the Pro and Business plans.",
                    Wan.button("See plans", Wan.Variant.PRIMARY, null, () -> shell.go(MainShell.Page.ACCOUNT))));
        }
        Label ff = Wan.text("Checking FFmpeg…", "muted");
        Wan.background(() -> s.ffmpeg().detect(), t -> ff.setText(t.map(x -> "FFmpeg " + x.version() + " · " + x.ffmpeg())
                .orElse("FFmpeg not found — install it or set its location in Settings → Media.")), e -> ff.setText(e.getMessage()));

        List<ConversionService.Preset> presets = Arrays.stream(ConversionService.Preset.values()).filter(p -> !audio || p.audioOnly()).toList();
        ComboBox<ConversionService.Preset> format = Wan.dropdown(presets, presets.getFirst());
        format.setConverter(new javafx.util.StringConverter<>() {
            public String toString(ConversionService.Preset p) { return p == null ? "" : label(p); }
            public ConversionService.Preset fromString(String x) { return null; }
        });
        Label chosen = Wan.text("No files chosen", "muted");
        Button pick = Wan.button(audio ? "Choose video or audio files…" : "Choose media files…", Wan.Variant.SECONDARY, Icons.FOLDER, () -> {
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Media", "*.mp4", "*.mkv", "*.webm", "*.mov", "*.ts", "*.avi",
                    "*.mp3", "*.m4a", "*.aac", "*.wav", "*.flac", "*.ogg", "*.opus"));
            List<File> f = fc.showOpenMultipleDialog(shell.stage());
            if (f != null) {
                files.clear();
                files.addAll(f);
                chosen.setText(f.size() == 1 ? f.getFirst().getName() : f.size() + " files (batch conversion)");
            }
        });
        TextField out = Wan.textField("");
        out.setText(s.settings().get().mediaOutputDir());
        Button outPick = Wan.button("Browse…", Wan.Variant.SECONDARY, Icons.FOLDER, () -> {
            File d = new DirectoryChooser().showDialog(shell.stage());
            if (d != null) out.setText(d.getAbsolutePath());
        });
        HBox outRow = new HBox(8, out, outPick);
        HBox.setHgrow(out, Priority.ALWAYS);
        Button start = Wan.button(audio ? "Extract audio" : "Convert", Wan.Variant.PRIMARY, Icons.CONVERT, () -> {
            if (files.isEmpty()) {
                shell.toast("error", "No input", "Choose at least one file.");
                return;
            }
            for (File f : List.copyOf(files)) {
                Wan.background(() -> s.conversions().submit(f.getAbsolutePath(), format.getValue().name(), out.getText()),
                        j -> reload(), e -> shell.toast("error", f.getName(), e.getMessage()));
            }
        });
        VBox form = Wan.card(Wan.text(audio ? "Audio Downloader" : "Convert media", "h2"),
                Wan.text(audio ? "Extract the audio track of a downloaded video, or convert audio files (MP3, M4A, AAC, WAV, FLAC). "
                        + "To grab audio from a website, use the Video Grabber and pick an \"Audio only\" format when the source offers one."
                        : "Convert or re-package downloaded media. Copy presets keep the original quality.", "muted"),
                new HBox(10, pick, chosen), Wan.field("Output format", format, null), Wan.field("Output folder", outRow, null),
                new HBox(10, start, Wan.spacer(), ff));
        page.getChildren().addAll(form, Wan.text("Conversion queue", "h2"), jobs);
        unsub = s.bus().subscribe(EventBus.CONVERSION, p -> Platform.runLater(this::reload));
        reload();
        return page;
    }

    static String label(ConversionService.Preset p) {
        return switch (p) {
            case MP4 -> "MP4 (H.264 + AAC, re-encode)";
            case MP4_COPY -> "MP4 (copy streams, no quality loss)";
            case MKV_COPY -> "MKV (copy streams)";
            case WEBM -> "WebM (VP9 + Opus)";
            case MP3 -> "MP3 audio 192 kbps";
            case M4A -> "M4A audio (AAC 192 kbps)";
            case AAC -> "AAC audio";
            case WAV -> "WAV (lossless PCM)";
            case FLAC -> "FLAC (lossless)";
        };
    }

    private void reload() {
        Wan.background(() -> shell.services().conversions().list(), list -> {
            jobs.getChildren().clear();
            if (list.isEmpty()) jobs.getChildren().add(Wan.text("No conversions yet.", "muted"));
            for (ConversionJob j : list.stream().limit(30).toList()) jobs.getChildren().add(row(j));
        }, e -> shell.toast("error", "Conversions", e.getMessage()));
    }

    private Node row(ConversionJob j) {
        Wan.Progress p = new Wan.Progress();
        p.setPrefWidth(200);
        p.set(j.getProgress(), switch (j.getStatus()) {
            case COMPLETED -> "done";
            case FAILED -> "failed";
            case CANCELLED -> "paused";
            default -> null;
        });
        Label status = Wan.chip(j.getStatus().name().charAt(0) + j.getStatus().name().substring(1).toLowerCase(Locale.ROOT),
                switch (j.getStatus()) { case COMPLETED -> "ok"; case FAILED -> "err"; case RUNNING -> "info"; default -> "free"; });
        VBox text = new VBox(2, Wan.text(Path.of(j.getInputPath()).getFileName() + " → " + Path.of(j.getOutputPath()).getFileName(), "h3"),
                Wan.text(j.getErrorMessage() != null ? j.getErrorMessage() : j.getOutputPath(), "muted", "small"));
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox r = new HBox(12, text, p, status);
        if (j.getStatus().name().equals("RUNNING") || j.getStatus().name().equals("QUEUED")) {
            r.getChildren().add(Wan.iconButton(Icons.STOP, "Cancel", () -> Wan.background(() -> {
                shell.services().conversions().cancel(j.getId());
                return true;
            }, ok -> { }, e -> shell.toast("error", "Cancel", e.getMessage()))));
        } else if (j.getStatus().name().equals("COMPLETED")) {
            r.getChildren().add(Wan.iconButton(Icons.FOLDER, "Open folder", () -> shell.openFolder(Path.of(j.getOutputPath()).getParent().toString())));
        }
        r.setAlignment(Pos.CENTER_LEFT);
        return Wan.card(r);
    }

    @Override
    public void dispose() {
        if (unsub != null) unsub.run();
    }
}
