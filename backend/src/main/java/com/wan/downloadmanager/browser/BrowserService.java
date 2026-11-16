package com.wan.downloadmanager.browser;

import com.wan.downloadmanager.download.DownloadService;
import com.wan.downloadmanager.dto.CreateDownloadRequest;
import com.wan.downloadmanager.dto.DownloadView;
import com.wan.downloadmanager.entitlement.EntitlementService;
import com.wan.downloadmanager.entitlement.Feature;
import com.wan.downloadmanager.entity.BrowserSession;
import com.wan.downloadmanager.exception.WdmException;
import com.wan.downloadmanager.repository.BrowserSessionRepository;
import com.wan.downloadmanager.storage.Settings;
import com.wan.downloadmanager.storage.SettingsService;
import org.springframework.stereotype.Service;

import java.util.*;

/** Browser extensions, as seen through the native messaging host. */
@Service
public class BrowserService {
    public static final Set<String> BROWSERS = Set.of("CHROME", "EDGE", "FIREFOX");

    public record Hello(String browser, String extensionVersion) {}

    public record Handoff(String url, String fileName, String pageUrl, String mimeType, String browser) {}

    public record BrowserStatus(String browser, boolean enabled, boolean connected, String extensionVersion, Long lastSeenAt) {}

    public record ExtensionConfig(boolean enabled, boolean videoDetection, boolean videoPopup, List<String> blockedSites,
                                  boolean detectionAllowedByPlan) {}

    private static final long CONNECTED_WINDOW_MS = 5 * 60_000;
    private final BrowserSessionRepository sessions;
    private final SettingsService settings;
    private final DownloadService downloads;
    private final EntitlementService entitlements;

    public BrowserService(BrowserSessionRepository sessions, SettingsService settings, DownloadService downloads, EntitlementService entitlements) {
        this.sessions = sessions;
        this.settings = settings;
        this.downloads = downloads;
        this.entitlements = entitlements;
    }

    public ExtensionConfig hello(Hello h) {
        String b = normalise(h.browser());
        BrowserSession s = sessions.findAll().stream().filter(x -> x.getBrowser().equals(b)).findFirst().orElseGet(() -> {
            BrowserSession n = new BrowserSession();
            n.setId(UUID.randomUUID().toString());
            n.setBrowser(b);
            n.setConnectedAt(System.currentTimeMillis());
            return n;
        });
        String ver = h.extensionVersion() == null ? null : h.extensionVersion().replaceAll("[^0-9A-Za-z.\\-]", "");
        s.setExtensionVer(ver == null ? null : ver.substring(0, Math.min(20, ver.length())));
        s.setLastSeenAt(System.currentTimeMillis());
        sessions.save(s);
        return config(b);
    }

    public ExtensionConfig config(String browser) {
        Settings st = settings.get();
        return new ExtensionConfig(enabled(st, normalise(browser)), st.videoDetection(), st.videoPopup(),
                st.blockedSites() == null ? List.of() : st.blockedSites(), entitlements.has(Feature.VIDEO_DETECTION));
    }

    public DownloadView handoff(Handoff h) {
        entitlements.require(Feature.BROWSER_INTEGRATION);
        if (!enabled(settings.get(), normalise(h.browser()))) throw WdmException.forbidden("browser_disabled", "Integration with this browser is turned off");
        return downloads.create(new CreateDownloadRequest(h.url(), h.fileName(), null, null, null, true, h.pageUrl(), null, false, "BROWSER"));
    }

    public List<BrowserStatus> status() {
        Settings st = settings.get();
        Map<String, BrowserSession> seen = new HashMap<>();
        sessions.findAll().forEach(s -> seen.put(s.getBrowser(), s));
        List<BrowserStatus> out = new ArrayList<>();
        for (String b : List.of("CHROME", "EDGE", "FIREFOX")) {
            BrowserSession s = seen.get(b);
            boolean connected = s != null && System.currentTimeMillis() - s.getLastSeenAt() < CONNECTED_WINDOW_MS;
            out.add(new BrowserStatus(b, enabled(st, b), connected, s == null ? null : s.getExtensionVer(), s == null ? null : s.getLastSeenAt()));
        }
        return out;
    }

    private static boolean enabled(Settings s, String b) {
        return switch (b) {
            case "CHROME" -> s.chromeEnabled();
            case "EDGE" -> s.edgeEnabled();
            case "FIREFOX" -> s.firefoxEnabled();
            default -> false;
        };
    }

    static String normalise(String b) {
        String x = b == null ? "" : b.toUpperCase(Locale.ROOT).trim();
        if (!BROWSERS.contains(x)) throw WdmException.badRequest("browser_unknown", "Unknown browser");
        return x;
    }
}
