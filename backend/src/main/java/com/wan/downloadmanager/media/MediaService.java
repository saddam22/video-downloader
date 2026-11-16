package com.wan.downloadmanager.media;

import com.wan.downloadmanager.download.DownloadService;
import com.wan.downloadmanager.dto.CreateDownloadRequest;
import com.wan.downloadmanager.dto.DownloadView;
import com.wan.downloadmanager.engine.EngineService;
import com.wan.downloadmanager.engine.HttpFetcher;
import com.wan.downloadmanager.entitlement.EntitlementService;
import com.wan.downloadmanager.entitlement.Feature;
import com.wan.downloadmanager.entity.MediaFormat;
import com.wan.downloadmanager.entity.MediaKind;
import com.wan.downloadmanager.entity.MediaSource;
import com.wan.downloadmanager.exception.WdmException;
import com.wan.downloadmanager.notification.EventBus;
import com.wan.downloadmanager.repository.MediaFormatRepository;
import com.wan.downloadmanager.repository.MediaSourceRepository;
import com.wan.downloadmanager.security.SafeFiles;
import com.wan.downloadmanager.security.UrlPolicy;
import com.wan.downloadmanager.storage.SettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Media the browser extension reports from a page. Every listed format is verified from the source
 * itself (HTTP probe, HLS/DASH manifest); a page's claim alone never creates a quality option.
 * DRM-protected and encrypted streams are recorded as protected and are not downloadable.
 */
@Service
public class MediaService {
    public static final int MAX_MANIFEST = 2 * 1024 * 1024;

    public record Report(@NotBlank @Size(max = 8192) String pageUrl, @Size(max = 500) String title, @Size(max = 4) String browser,
                         @Size(max = 50) List<@Valid SourceReport> sources) {}

    /** width/height/duration come from the decoded HTMLVideoElement (videoWidth/videoHeight/duration). */
    public record SourceReport(@NotBlank @Size(max = 8192) String url, @Size(max = 100) String mimeType, Integer width, Integer height,
                               Double durationSec, @Size(max = 8192) String poster) {}

    public record FormatView(String id, String label, String container, Integer width, Integer height, Long bandwidth,
                             String videoCodec, String audioCodec, Long estimatedBytes) {}

    public record SourceView(String id, String pageUrl, String sourceUrl, String title, String kind, Double durationSec,
                             String thumbnail, boolean protectedContent, String note, List<FormatView> formats, long detectedAt) {}

    private final MediaSourceRepository sources;
    private final MediaFormatRepository formats;
    private final UrlPolicy policy;
    private final HttpFetcher fetcher;
    private final DownloadService downloads;
    private final EntitlementService entitlements;
    private final SettingsService settings;
    private final EventBus bus;

    public MediaService(MediaSourceRepository sources, MediaFormatRepository formats, UrlPolicy policy, EngineService engine,
                        DownloadService downloads, EntitlementService entitlements, SettingsService settings, EventBus bus) {
        this.sources = sources;
        this.formats = formats;
        this.policy = policy;
        this.fetcher = engine.fetcher();
        this.downloads = downloads;
        this.entitlements = entitlements;
        this.settings = settings;
        this.bus = bus;
    }

    public List<SourceView> detect(Report r) {
        entitlements.require(Feature.VIDEO_DETECTION);
        if (!settings.get().videoDetection()) throw WdmException.forbidden("detection_disabled", "Video detection is turned off in settings");
        URI page = policy.parse(r.pageUrl());
        if (blocked(page.getHost())) throw WdmException.forbidden("site_blocked", "Detection is disabled for " + page.getHost());
        List<SourceView> out = new ArrayList<>();
        for (SourceReport s : r.sources() == null ? List.<SourceReport>of() : r.sources()) {
            if (s.url().startsWith("blob:") || s.url().startsWith("data:")) continue; // media-source buffers: no URL to fetch
            URI uri;
            try {
                uri = policy.validate(s.url());
            } catch (WdmException e) {
                continue;
            }
            out.add(register(page, uri, r.title(), r.browser(), s));
        }
        return out;
    }

    private SourceView register(URI page, URI uri, String title, String browser, SourceReport s) {
        String fp = fingerprint(uri);
        Optional<MediaSource> existing = sources.findByFingerprint(fp);
        if (existing.isPresent()) {
            MediaSource m = existing.get();
            m.setDetectedAt(System.currentTimeMillis());
            sources.save(m);
            return view(m);
        }
        MediaKind kind = kindOf(uri, s.mimeType());
        MediaSource m = new MediaSource();
        m.setId(UUID.randomUUID().toString());
        m.setPageUrl(page.toString());
        m.setSourceUrl(uri.toString());
        m.setTitle(title == null || title.isBlank() ? null : title.strip());
        m.setMimeType(s.mimeType());
        m.setKind(kind);
        m.setDurationSec(s.durationSec() != null && s.durationSec() > 0 && s.durationSec() < 1e7 ? s.durationSec() : null);
        m.setThumbnail(s.poster() != null && s.poster().startsWith("https://") ? s.poster() : null);
        m.setBrowser(browser);
        m.setFingerprint(fp);
        m.setDetectedAt(System.currentTimeMillis());
        List<MediaFormat> verified = new ArrayList<>();
        String note = null;
        try {
            switch (kind) {
                case FILE -> verified.addAll(verifyFile(m, uri, s));
                case HLS -> note = verifyHls(m, uri, verified);
                case DASH -> note = verifyDash(m, uri, verified);
            }
        } catch (Exception e) {
            note = "Could not verify this source: " + e.getMessage();
        }
        if (verified.isEmpty() && note == null) note = "No downloadable format could be verified";
        if (note != null) m.setMimeType((m.getMimeType() == null ? "" : m.getMimeType()) + ";note=" + note);
        sources.save(m);
        formats.saveAll(verified);
        SourceView v = view(m);
        bus.publish(EventBus.MEDIA, v);
        return v;
    }

    private List<MediaFormat> verifyFile(MediaSource m, URI uri, SourceReport s) throws Exception {
        HttpFetcher.Probe p = fetcher.probe(uri, Map.of("Referer", m.getPageUrl()));
        String type = p.contentType() == null ? "" : p.contentType().toLowerCase(Locale.ROOT);
        String ext = SafeFiles.extension(p.fileName());
        boolean media = type.startsWith("video/") || type.startsWith("audio/")
                || (type.equals("application/octet-stream") && Set.of("mp4", "webm", "mkv", "mov", "m4v", "mp3", "m4a", "ogg").contains(ext));
        if (!media) return List.of();
        String container = type.startsWith("video/") || type.startsWith("audio/") ? type.substring(type.indexOf('/') + 1) : ext;
        MediaFormat f = format(m, uri, container, s.width(), s.height(), null, null, null, p.totalBytes());
        f.setLabel(label(s.height(), container, p.totalBytes(), null));
        return List.of(f);
    }

    private String verifyHls(MediaSource m, URI uri, List<MediaFormat> out) throws Exception {
        String text = fetchText(uri, m.getPageUrl());
        ManifestParser.HlsMaster master = ManifestParser.parseHlsMaster(text, uri);
        if (master.protectedContent()) return "Protected (encrypted) stream — not downloadable";
        if (master.isMediaPlaylist()) {
            ManifestParser.HlsMedia media = ManifestParser.parseHlsMedia(text, uri);
            if (media.protectedContent()) return "Protected (encrypted) stream — not downloadable";
            if (!media.endList()) return "Live stream — not supported";
            if (m.getDurationSec() == null) m.setDurationSec(media.durationSec());
            MediaFormat f = format(m, uri, "hls", null, null, null, null, null, null);
            f.setLabel("Stream (single quality) · HLS");
            out.add(f);
            return null;
        }
        Map<String, URI> audioByGroup = new HashMap<>();
        master.audio().forEach(a -> audioByGroup.putIfAbsent(a.groupId(), a.uri()));
        for (ManifestParser.Variant v : master.variants()) {
            String[] codecs = splitCodecs(v.codecs());
            MediaFormat f = format(m, v.uri(), "hls", v.width(), v.height(), v.bandwidth(), codecs[0], codecs[1], null);
            f.setLabel(label(v.height(), "HLS", null, v.bandwidth()));
            URI audio = v.audioGroup() != null ? audioByGroup.get(v.audioGroup()) : null;
            if (audio != null) f.setContainer("hls+audio=" + audio);
            out.add(f);
        }
        out.sort(Comparator.comparing((MediaFormat f) -> f.getHeight() == null ? 0 : f.getHeight()).reversed());
        return null;
    }

    private String verifyDash(MediaSource m, URI uri, List<MediaFormat> out) throws Exception {
        ManifestParser.Dash dash = ManifestParser.parseDash(fetchText(uri, m.getPageUrl()), uri);
        if (dash.protectedContent()) return "DRM-protected (ContentProtection) — not downloadable";
        if (m.getDurationSec() == null) m.setDurationSec(dash.durationSec());
        boolean segmentedOnly = true;
        for (ManifestParser.DashRepresentation r : dash.representations()) {
            if (r.singleFileUrl() == null) continue;
            segmentedOnly = false;
            policy.validate(r.singleFileUrl().toString());
            String container = r.mimeType() == null ? "dash" : r.mimeType().substring(r.mimeType().indexOf('/') + 1);
            boolean audioOnly = r.mimeType() != null && r.mimeType().startsWith("audio/");
            String[] codecs = splitCodecs(r.codecs());
            MediaFormat f = format(m, r.singleFileUrl(), container, r.width(), r.height(), r.bandwidth(),
                    audioOnly ? null : codecs[0], audioOnly ? codecs[0] : codecs[1], null);
            f.setLabel(audioOnly ? "Audio only · " + container + bitrate(r.bandwidth()) : label(r.height(), container, null, r.bandwidth()) + " (video track)");
            out.add(f);
        }
        if (segmentedOnly) return "Segmented DASH is not supported yet; only single-file DASH representations can be downloaded";
        return null;
    }

    public List<SourceView> list() {
        return sources.findTop200ByOrderByDetectedAtDesc().stream().map(this::view).toList();
    }

    public SourceView get(String id) {
        return view(sources.findById(id).orElseThrow(() -> WdmException.notFound("Media source")));
    }

    public void clear() {
        sources.deleteAll();
    }

    /** Starts downloading one verified format. */
    public DownloadView download(String sourceId, String formatId) {
        entitlements.require(Feature.VIDEO_DETECTION);
        MediaSource m = sources.findById(sourceId).orElseThrow(() -> WdmException.notFound("Media source"));
        MediaFormat f = formats.findById(formatId).filter(x -> x.getMediaSourceId().equals(sourceId))
                .orElseThrow(() -> WdmException.notFound("Format"));
        List<MediaFormat> all = formats.findByMediaSourceId(sourceId);
        if (all.size() > 1 && !entitlements.has(Feature.VIDEO_QUALITY)) {
            MediaFormat best = all.getFirst();
            if (!best.getId().equals(f.getId())) throw WdmException.entitlement(Feature.VIDEO_QUALITY.key());
        }
        String base = SafeFiles.sanitize(m.getTitle() != null ? m.getTitle() : "video");
        if (f.getContainer() != null && f.getContainer().startsWith("hls")) {
            Map<String, String> internal = new LinkedHashMap<>();
            internal.put(HlsRunner.HDR_REFERER, m.getPageUrl());
            if (f.getContainer().startsWith("hls+audio=")) internal.put(HlsRunner.HDR_AUDIO, f.getContainer().substring("hls+audio=".length()));
            String quality = f.getHeight() != null ? " " + f.getHeight() + "p" : "";
            return downloads.createExternal(f.getFormatUrl(), m.getPageUrl(), base + quality + ".mp4", HlsRunner.MIME, internal);
        }
        String ext = switch (f.getContainer() == null ? "" : f.getContainer()) {
            case "webm" -> "webm";
            case "mpeg", "mp3" -> "mp3";
            case "ogg" -> "ogg";
            case "x-matroska", "mkv" -> "mkv";
            case "mp4a-latm", "aac", "m4a" -> "m4a";
            default -> "mp4";
        };
        String quality = f.getHeight() != null ? " " + f.getHeight() + "p" : "";
        return downloads.create(new CreateDownloadRequest(f.getFormatUrl(), base + quality + "." + ext, null, null, null, true,
                m.getPageUrl(), null, true, "MEDIA"));
    }

    // ---------------------------------------------------------------- helpers

    private String fetchText(URI uri, String referer) throws Exception {
        var res = fetcher.open(uri, Map.of("Referer", referer), 0, null, null);
        try (InputStream in = res.body()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
                if (buf.size() > MAX_MANIFEST) throw new IOException("Manifest too large");
            }
            return buf.toString(StandardCharsets.UTF_8);
        }
    }

    private boolean blocked(String host) {
        List<String> list = settings.get().blockedSites();
        if (list == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return list.stream().map(x -> x.toLowerCase(Locale.ROOT).trim()).anyMatch(b -> !b.isEmpty() && (h.equals(b) || h.endsWith("." + b)));
    }

    static MediaKind kindOf(URI uri, String mime) {
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (path.endsWith(".m3u8") || m.contains("mpegurl")) return MediaKind.HLS;
        if (path.endsWith(".mpd") || m.contains("dash+xml")) return MediaKind.DASH;
        return MediaKind.FILE;
    }

    static String fingerprint(URI uri) {
        try {
            String norm = new URI(uri.getScheme().toLowerCase(Locale.ROOT), uri.getRawAuthority().toLowerCase(Locale.ROOT),
                    uri.getRawPath(), uri.getRawQuery(), null).toString();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(norm.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MediaFormat format(MediaSource m, URI url, String container, Integer w, Integer h, Long bw, String vc, String ac, Long size) {
        MediaFormat f = new MediaFormat();
        f.setId(UUID.randomUUID().toString());
        f.setMediaSourceId(m.getId());
        f.setFormatUrl(url.toString());
        f.setContainer(container);
        f.setWidth(w);
        f.setHeight(h);
        f.setBandwidth(bw);
        f.setVideoCodec(vc);
        f.setAudioCodec(ac);
        f.setEstimatedBytes(size);
        return f;
    }

    private static String[] splitCodecs(String codecs) {
        String v = null, a = null;
        if (codecs != null) {
            for (String c : codecs.split(",")) {
                String x = c.trim();
                if (x.startsWith("mp4a") || x.startsWith("opus") || x.startsWith("ac-3") || x.startsWith("ec-3") || x.startsWith("vorbis")) a = x;
                else if (!x.isEmpty()) v = x;
            }
        }
        return new String[]{v, a};
    }

    private static String label(Integer height, String container, Long size, Long bandwidth) {
        StringBuilder sb = new StringBuilder();
        if (height != null) sb.append(height).append("p · ");
        sb.append(container.toUpperCase(Locale.ROOT));
        if (size != null) sb.append(" · ").append(String.format(Locale.ROOT, "%.1f MB", size / 1048576.0));
        sb.append(bitrate(bandwidth));
        return sb.toString();
    }

    private static String bitrate(Long bandwidth) {
        return bandwidth == null ? "" : String.format(Locale.ROOT, " · %.1f Mbps", bandwidth / 1e6);
    }

    private SourceView view(MediaSource m) {
        String mime = m.getMimeType();
        String note = null;
        if (mime != null && mime.contains(";note=")) {
            note = mime.substring(mime.indexOf(";note=") + 6);
        }
        boolean prot = note != null && (note.startsWith("Protected") || note.startsWith("DRM"));
        List<FormatView> fs = formats.findByMediaSourceId(m.getId()).stream()
                .sorted(Comparator.comparing((MediaFormat f) -> f.getHeight() == null ? 0 : f.getHeight()).reversed())
                .map(f -> new FormatView(f.getId(), f.getLabel(), f.getContainer() != null && f.getContainer().startsWith("hls") ? "hls" : f.getContainer(),
                        f.getWidth(), f.getHeight(), f.getBandwidth(), f.getVideoCodec(), f.getAudioCodec(), f.getEstimatedBytes()))
                .toList();
        return new SourceView(m.getId(), m.getPageUrl(), m.getSourceUrl(), m.getTitle(), m.getKind().name(), m.getDurationSec(),
                m.getThumbnail(), prot, note, fs, m.getDetectedAt());
    }
}
