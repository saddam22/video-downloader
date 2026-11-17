package com.wan.downloadmanager.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wan.downloadmanager.exception.WdmException;
import com.wan.downloadmanager.storage.SettingsService;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleConsumer;

/**
 * FFmpeg / FFprobe access. Commands are argument lists for {@link ProcessBuilder} (never a shell),
 * inputs are local files only ({@code -protocol_whitelist file}), and executables come from the
 * user's configured path or the system PATH.
 */
@Service
public class FfmpegService {
    public record Tools(String ffmpeg, String ffprobe, String version) {}

    public record ProbeInfo(double durationSec, Integer width, Integer height, String videoCodec, String audioCodec, String formatName, long sizeBytes) {}

    private final SettingsService settings;
    private final ObjectMapper mapper;

    public FfmpegService(SettingsService settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    public Optional<Tools> detect() {
        String ff = resolve(settings.get().ffmpegPath(), "ffmpeg");
        String fp = resolve(settings.get().ffprobePath(), "ffprobe");
        if (ff == null || fp == null) return Optional.empty();
        try {
            Process p = new ProcessBuilder(ff, "-hide_banner", "-version").redirectErrorStream(true).start();
            String first = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)).readLine();
            if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0 || first == null) return Optional.empty();
            return Optional.of(new Tools(ff, fp, first.replaceFirst("^ffmpeg version\\s+", "").split(" ")[0]));
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    public Tools require() {
        return detect().orElseThrow(() -> WdmException.unavailable("ffmpeg_missing",
                "FFmpeg was not found. Install FFmpeg or set its location in Settings → Media."));
    }

    public ProbeInfo probe(Path file) {
        Tools t = require();
        List<String> cmd = List.of(t.ffprobe(), "-v", "error", "-protocol_whitelist", "file", "-print_format", "json",
                "-show_format", "-show_streams", file.toAbsolutePath().toString());
        try {
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) throw WdmException.badRequest("ffprobe_failed", "Not a readable media file");
            JsonNode n = mapper.readTree(out);
            Integer w = null, h = null;
            String vc = null, ac = null;
            for (JsonNode s : n.path("streams")) {
                if ("video".equals(s.path("codec_type").asText()) && vc == null) {
                    vc = s.path("codec_name").asText(null);
                    w = s.has("width") ? s.get("width").asInt() : null;
                    h = s.has("height") ? s.get("height").asInt() : null;
                }
                if ("audio".equals(s.path("codec_type").asText()) && ac == null) ac = s.path("codec_name").asText(null);
            }
            return new ProbeInfo(n.path("format").path("duration").asDouble(0), w, h, vc, ac,
                    n.path("format").path("format_name").asText(null), Files.size(file));
        } catch (IOException e) {
            throw WdmException.badRequest("ffprobe_failed", "Could not read media file: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw WdmException.unavailable("interrupted", "Interrupted");
        }
    }

    /**
     * Runs ffmpeg with the given arguments (inserted after the fixed safe prefix) and reports progress
     * 0..1 from {@code -progress}. Returns the process so callers can cancel it.
     */
    public Process start(List<String> args) throws IOException {
        Tools t = require();
        List<String> cmd = new ArrayList<>(List.of(t.ffmpeg(), "-hide_banner", "-nostdin", "-y", "-protocol_whitelist", "file",
                "-progress", "pipe:1", "-nostats", "-loglevel", "error"));
        cmd.addAll(args);
        return new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.PIPE).start();
    }

    /** Waits for completion, feeding progress; returns stderr text when it fails. */
    public String await(Process p, double durationSec, DoubleConsumer progress) throws IOException, InterruptedException {
        StringBuilder err = new StringBuilder();
        Thread errReader = Thread.ofVirtual().start(() -> {
            try (var r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) if (err.length() < 4000) err.append(l).append('\n');
            } catch (IOException ignored) {
                // process ended
            }
        });
        try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("out_time_us=") && durationSec > 0) {
                    try {
                        progress.accept(Math.min(1.0, Long.parseLong(l.substring(12).trim()) / 1e6 / durationSec));
                    } catch (NumberFormatException ignored) {
                        // "N/A" at the start
                    }
                }
            }
        }
        int code = p.waitFor();
        errReader.join(2000);
        return code == 0 ? null : (err.isEmpty() ? "ffmpeg exited with code " + code : err.toString().trim());
    }

    private static String resolve(String configured, String name) {
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured.trim());
            return Files.isRegularFile(p) && Files.isExecutable(p) ? p.toString() : null;
        }
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? name + ".exe" : name;
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(dir, exe);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toString();
        }
        return null;
    }
}
