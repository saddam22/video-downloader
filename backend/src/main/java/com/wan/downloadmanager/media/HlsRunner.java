package com.wan.downloadmanager.media;

import com.wan.downloadmanager.download.DownloadService;
import com.wan.downloadmanager.engine.EngineService;
import com.wan.downloadmanager.engine.HttpFetcher;
import com.wan.downloadmanager.entity.Download;
import com.wan.downloadmanager.entity.DownloadHeader;
import com.wan.downloadmanager.repository.DownloadHeaderRepository;
import com.wan.downloadmanager.security.Redactor;
import com.wan.downloadmanager.security.SafeFiles;
import com.wan.downloadmanager.security.UrlPolicy;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads an unencrypted, finished (VOD) HLS rendition: every segment is fetched through the same
 * SSRF-checked {@link HttpFetcher}, appended in order, then remuxed to MP4 by FFmpeg from the local
 * file (FFmpeg never touches the network). A separate audio rendition is downloaded and merged.
 * Without FFmpeg the result is kept as an MPEG-TS file. A stop restarts the stream from the beginning.
 */
@Component
public class HlsRunner implements DownloadService.ExternalRunner {
    private static final Logger log = LoggerFactory.getLogger(HlsRunner.class);
    public static final String MIME = "application/vnd.apple.mpegurl";
    static final String HDR_AUDIO = "x-wdm-hls-audio";
    static final String HDR_REFERER = "x-wdm-referer";

    private final DownloadService downloads;
    private final DownloadHeaderRepository headers;
    private final HttpFetcher fetcher;
    private final UrlPolicy policy;
    private final FfmpegService ffmpeg;
    private final ConversionService conversions;
    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();

    public HlsRunner(DownloadService downloads, DownloadHeaderRepository headers, EngineService engine, UrlPolicy policy,
                     FfmpegService ffmpeg, ConversionService conversions) {
        this.downloads = downloads;
        this.headers = headers;
        this.fetcher = engine.fetcher();
        this.policy = policy;
        this.ffmpeg = ffmpeg;
        this.conversions = conversions;
    }

    @PostConstruct
    void register() {
        downloads.setExternalRunner(this);
    }

    @Override public boolean handles(Download d) { return MIME.equals(d.getMimeType()); }
    @Override public boolean isRunning(String id) { return running.containsKey(id); }
    @Override public int activeCount() { return running.size(); }

    @Override
    public void stop(String id) {
        AtomicBoolean flag = running.get(id);
        if (flag != null) flag.set(true);
    }

    @Override
    public void start(Download d) {
        AtomicBoolean stop = new AtomicBoolean();
        if (running.putIfAbsent(d.getId(), stop) != null) return;
        Thread.ofVirtual().name("wdm-hls-" + d.getId()).start(() -> {
            try {
                run(d, stop);
            } finally {
                running.remove(d.getId());
                downloads.pump();
            }
        });
    }

    private void run(Download d, AtomicBoolean stop) {
        String id = d.getId();
        Map<String, String> internal = new java.util.HashMap<>();
        for (DownloadHeader h : headers.findByDownloadId(id)) internal.put(h.getName(), h.getValue());
        Map<String, String> req = internal.containsKey(HDR_REFERER) ? Map.of("Referer", internal.get(HDR_REFERER)) : Map.of();
        Path dir = Path.of(d.getSaveDir());
        Path videoTs = dir.resolve(id + ".video.wdmpart");
        Path audioTs = dir.resolve(id + ".audio.wdmpart");
        try {
            ManifestParser.HlsMedia video = media(URI.create(d.getUrl()), req);
            ManifestParser.HlsMedia audio = internal.containsKey(HDR_AUDIO) ? media(policy.validate(internal.get(HDR_AUDIO)), req) : null;
            int total = video.segments().size() + (audio == null ? 0 : audio.segments().size());
            long[] bytes = {0};
            int[] done = {0};
            long started = System.nanoTime();
            Progress p = n -> {
                bytes[0] += n;
                long secs = Math.max(1, (System.nanoTime() - started) / 1_000_000_000L);
                // Total is unknown until the end; progress is shown as segment count via downloaded/estimated bytes.
                Long estimate = done[0] > 0 ? bytes[0] * total / Math.max(1, done[0]) : null;
                downloads.externalProgress(id, bytes[0], estimate, bytes[0] / secs);
            };
            Files.deleteIfExists(videoTs);
            if (!fetchAll(video, videoTs, req, stop, p, done)) return;
            if (audio != null) {
                Files.deleteIfExists(audioTs);
                if (!fetchAll(audio, audioTs, req, stop, p, done)) return;
            }
            String stem = d.getFileName().replaceAll("\\.mp4$", "");
            Path out;
            if (ffmpeg.detect().isPresent()) {
                out = SafeFiles.uniqueTarget(dir, stem + ".mp4");
                Path tmpOut = dir.resolve(id + ".out.mp4");
                if (audio != null) conversions.mergeBlocking(videoTs, audioTs, tmpOut);
                else conversions.remuxBlocking(videoTs, tmpOut);
                Files.move(tmpOut, out, StandardCopyOption.ATOMIC_MOVE);
            } else {
                out = SafeFiles.uniqueTarget(dir, stem + ".ts");
                Files.move(videoTs, out, StandardCopyOption.ATOMIC_MOVE);
            }
            Files.deleteIfExists(videoTs);
            Files.deleteIfExists(audioTs);
            downloads.externalCompleted(id, out, Files.size(out), sha256(out));
        } catch (Exception e) {
            log.warn("HLS download {} failed: {}", id, Redactor.redact(e.toString()));
            cleanup(videoTs, audioTs);
            downloads.externalFailed(id, Redactor.redact(e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private interface Progress {
        void add(long n);
    }

    private ManifestParser.HlsMedia media(URI uri, Map<String, String> req) throws Exception {
        var res = fetcher.open(uri, req, 0, null, null);
        String text;
        try (InputStream in = res.body()) {
            byte[] b = in.readNBytes(MediaService.MAX_MANIFEST + 1);
            if (b.length > MediaService.MAX_MANIFEST) throw new IOException("Playlist too large");
            text = new String(b, StandardCharsets.UTF_8);
        }
        ManifestParser.HlsMedia m = ManifestParser.parseHlsMedia(text, res.request().uri());
        if (m.protectedContent()) throw new IOException("This stream is encrypted; protected content is not downloaded");
        if (!m.endList()) throw new IOException("Live streams are not supported");
        if (m.segments().isEmpty()) throw new IOException("The playlist has no segments");
        return m;
    }

    private boolean fetchAll(ManifestParser.HlsMedia m, Path target, Map<String, String> req, AtomicBoolean stop, Progress p, int[] done)
            throws Exception {
        List<URI> uris = new java.util.ArrayList<>();
        if (m.initSegment() != null) uris.add(m.initSegment());
        m.segments().forEach(s -> uris.add(s.uri()));
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (URI u : uris) {
                if (stop.get()) {
                    cleanup(target);
                    downloads.externalStopped(idOf(target));
                    return false;
                }
                policy.validate(u.toString());
                IOException last = null;
                for (int attempt = 0; attempt < 4; attempt++) {
                    try {
                        var res = fetcher.open(u, req, 0, null, null);
                        try (InputStream in = res.body()) {
                            byte[] b = in.readAllBytes();
                            out.write(b);
                            p.add(b.length);
                        }
                        last = null;
                        break;
                    } catch (HttpFetcher.HttpStatusException e) {
                        if (!e.retryable()) throw e;
                        last = e;
                    } catch (IOException e) {
                        last = e;
                    }
                    Thread.sleep(500L << attempt);
                }
                if (last != null) throw last;
                done[0]++;
            }
        }
        return true;
    }

    private static String idOf(Path temp) {
        String n = temp.getFileName().toString();
        return n.substring(0, n.indexOf('.'));
    }

    private static void cleanup(Path... files) {
        for (Path f : files) {
            try {
                Files.deleteIfExists(f);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
