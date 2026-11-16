package com.wan.downloadmanager.media;

import com.wan.downloadmanager.entitlement.EntitlementService;
import com.wan.downloadmanager.entitlement.Feature;
import com.wan.downloadmanager.entity.ConversionJob;
import com.wan.downloadmanager.entity.JobStatus;
import com.wan.downloadmanager.exception.WdmException;
import com.wan.downloadmanager.notification.EventBus;
import com.wan.downloadmanager.notification.EventLog;
import com.wan.downloadmanager.repository.ConversionJobRepository;
import com.wan.downloadmanager.security.SafeFiles;
import com.wan.downloadmanager.storage.SettingsService;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Conversion queue (one FFmpeg process at a time) with real progress, cancellation and output validation. */
@Service
public class ConversionService {
    /** Presets: output extension and the ffmpeg arguments between input and output. */
    public enum Preset {
        MP4("mp4", List.of("-map", "0:v:0?", "-map", "0:a:0?", "-c:v", "libx264", "-preset", "veryfast", "-crf", "22", "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart")),
        MP4_COPY("mp4", List.of("-map", "0", "-c", "copy", "-movflags", "+faststart")),
        MKV_COPY("mkv", List.of("-map", "0", "-c", "copy")),
        WEBM("webm", List.of("-c:v", "libvpx-vp9", "-b:v", "0", "-crf", "33", "-c:a", "libopus", "-b:a", "128k")),
        MP3("mp3", List.of("-vn", "-c:a", "libmp3lame", "-b:a", "192k")),
        M4A("m4a", List.of("-vn", "-c:a", "aac", "-b:a", "192k")),
        AAC("aac", List.of("-vn", "-c:a", "aac", "-b:a", "192k")),
        WAV("wav", List.of("-vn", "-c:a", "pcm_s16le")),
        FLAC("flac", List.of("-vn", "-c:a", "flac"));

        final String ext;
        final List<String> args;

        Preset(String ext, List<String> args) {
            this.ext = ext;
            this.args = args;
        }

        public boolean audioOnly() {
            return args.contains("-vn");
        }
    }

    private final ConversionJobRepository jobs;
    private final FfmpegService ffmpeg;
    private final SettingsService settings;
    private final EntitlementService entitlements;
    private final EventBus bus;
    private final EventLog events;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("wdm-convert").factory());
    private final Map<String, Process> running = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    public ConversionService(ConversionJobRepository jobs, FfmpegService ffmpeg, SettingsService settings,
                             EntitlementService entitlements, EventBus bus, EventLog events) {
        this.jobs = jobs;
        this.ffmpeg = ffmpeg;
        this.settings = settings;
        this.entitlements = entitlements;
        this.bus = bus;
        this.events = events;
    }

    public List<ConversionJob> list() {
        return jobs.findAllByOrderByCreatedAtDesc();
    }

    public ConversionJob submit(String input, String presetName, String outputDir) {
        entitlements.require(Feature.MEDIA_CONVERSION);
        ffmpeg.require();
        Path in = Path.of(input).toAbsolutePath().normalize();
        if (!Files.isRegularFile(in)) throw WdmException.badRequest("input_missing", "Input file does not exist");
        Preset preset;
        try {
            preset = Preset.valueOf(presetName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw WdmException.badRequest("preset_invalid", "Unknown format " + presetName);
        }
        Path dir = Path.of(outputDir != null && !outputDir.isBlank() ? outputDir : settings.get().mediaOutputDir()).toAbsolutePath().normalize();
        SafeFiles.ensureDirectory(dir);
        String name = in.getFileName().toString();
        String stem = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        Path out = SafeFiles.uniqueTarget(dir, stem + "." + preset.ext);
        if (out.equals(in)) throw WdmException.badRequest("same_file", "Output would overwrite the input");
        ConversionJob j = new ConversionJob();
        j.setId(UUID.randomUUID().toString());
        j.setInputPath(in.toString());
        j.setOutputPath(out.toString());
        j.setPreset(preset.name());
        j.setStatus(JobStatus.QUEUED);
        j.setCreatedAt(System.currentTimeMillis());
        j.setUpdatedAt(System.currentTimeMillis());
        jobs.save(j);
        bus.publish(EventBus.CONVERSION, j);
        worker.submit(() -> run(j.getId()));
        return j;
    }

    /** Merges a video-only and an audio-only file without re-encoding (used for split HLS/DASH streams). */
    public void mergeBlocking(Path video, Path audio, Path out) throws Exception {
        Process p = ffmpeg.start(List.of("-i", video.toString(), "-i", audio.toString(), "-map", "0:v:0", "-map", "1:a:0", "-c", "copy",
                "-movflags", "+faststart", out.toString()));
        String err = ffmpeg.await(p, 0, x -> {});
        if (err != null) throw new IllegalStateException("Merge failed: " + err);
    }

    /** Remuxes an MPEG-TS file to MP4 without re-encoding. */
    public void remuxBlocking(Path in, Path out) throws Exception {
        Process p = ffmpeg.start(List.of("-i", in.toString(), "-map", "0", "-c", "copy", "-movflags", "+faststart", out.toString()));
        String err = ffmpeg.await(p, 0, x -> {});
        if (err != null) throw new IllegalStateException("Remux failed: " + err);
    }

    public void cancel(String id) {
        cancelled.add(id);
        Process p = running.get(id);
        if (p != null) {
            p.destroy();
            p.onExit().orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).exceptionally(t -> {
                p.destroyForcibly();
                return p;
            });
        }
        jobs.findById(id).ifPresent(j -> {
            if (j.getStatus() == JobStatus.QUEUED) update(j, JobStatus.CANCELLED, j.getProgress(), null);
        });
    }

    private void run(String id) {
        ConversionJob j = jobs.findById(id).orElse(null);
        if (j == null || cancelled.remove(id) || j.getStatus() != JobStatus.QUEUED) return;
        Path out = Path.of(j.getOutputPath());
        try {
            Preset preset = Preset.valueOf(j.getPreset());
            double duration = ffmpeg.probe(Path.of(j.getInputPath())).durationSec();
            update(j, JobStatus.RUNNING, 0, null);
            List<String> args = new ArrayList<>(List.of("-i", j.getInputPath()));
            args.addAll(preset.args);
            args.add(out.toString());
            Process p = ffmpeg.start(args);
            running.put(id, p);
            if (cancelled.contains(id)) p.destroy(); // cancel raced with start
            long[] lastPublish = {0};
            String err = ffmpeg.await(p, duration, f -> {
                if (System.currentTimeMillis() - lastPublish[0] > 500) {
                    lastPublish[0] = System.currentTimeMillis();
                    update(j, JobStatus.RUNNING, f, null);
                }
            });
            running.remove(id);
            if (cancelled.remove(id)) {
                Files.deleteIfExists(out);
                update(j, JobStatus.CANCELLED, j.getProgress(), null);
                return;
            }
            if (err != null) throw new IllegalStateException(err);
            var info = ffmpeg.probe(out); // output validation
            if (info.sizeBytes() == 0 || (duration > 0 && info.durationSec() <= 0)) throw new IllegalStateException("Output file is empty or unreadable");
            update(j, JobStatus.COMPLETED, 1.0, null);
            events.info("conversion", "Converted " + Path.of(j.getInputPath()).getFileName() + " → " + out.getFileName(), id);
            bus.notify("success", "Conversion finished", out.getFileName().toString(), id);
        } catch (Exception e) {
            running.remove(id);
            try {
                Files.deleteIfExists(out);
            } catch (Exception ignored) {
                // nothing to clean
            }
            if (cancelled.remove(id)) {
                update(j, JobStatus.CANCELLED, j.getProgress(), null);
                return;
            }
            update(j, JobStatus.FAILED, j.getProgress(), e.getMessage());
            events.error("conversion", "Conversion failed: " + e.getMessage(), id);
        }
    }

    private void update(ConversionJob j, JobStatus s, double progress, String error) {
        j.setStatus(s);
        j.setProgress(progress);
        j.setErrorMessage(error == null ? null : error.length() > 1000 ? error.substring(0, 1000) : error);
        j.setUpdatedAt(System.currentTimeMillis());
        jobs.save(j);
        bus.publish(EventBus.CONVERSION, j);
    }
}
