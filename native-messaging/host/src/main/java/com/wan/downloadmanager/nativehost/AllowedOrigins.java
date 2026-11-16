package com.wan.downloadmanager.nativehost;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Extension identities allowed to use the host. Store-assigned ids are only known once the
 * extensions are published; until then the ids of the development builds are configured in
 * {@code allowed-origins.properties} (packaged next to the host). Nothing is allowed by default.
 */
final class AllowedOrigins {
    /** Chrome-style ids that belong to the Edge Add-ons build (filled at release time). */
    static final Set<String> EDGE_IDS = new HashSet<>();

    private AllowedOrigins() {}

    static Set<String> load() {
        Set<String> out = new HashSet<>();
        Properties p = new Properties();
        try (InputStream in = AllowedOrigins.class.getResourceAsStream("/allowed-origins.properties")) {
            if (in != null) p.load(in);
        } catch (IOException ignored) {
            // nothing allowed
        }
        // Per-user additions (written by the installer / developer setup for unpacked builds).
        java.nio.file.Path user = NativeHost.dataDir().resolve("allowed-origins.properties");
        if (java.nio.file.Files.isRegularFile(user)) {
            Properties extra = new Properties();
            try (InputStream in = java.nio.file.Files.newInputStream(user)) {
                extra.load(in);
                for (String k : new String[]{"chrome", "edge", "firefox"}) {
                    String joined = String.join(",", p.getProperty(k, ""), extra.getProperty(k, ""));
                    p.setProperty(k, joined);
                }
            } catch (IOException ignored) {
                // keep packaged list only
            }
        }
        for (String key : new String[]{"chrome", "edge", "firefox"}) {
            for (String id : p.getProperty(key, "").split(",")) {
                String v = id.trim();
                if (v.isEmpty()) continue;
                if (key.equals("firefox")) out.add(v);
                else {
                    out.add("chrome-extension://" + v + "/");
                    if (key.equals("edge")) EDGE_IDS.add(v);
                }
            }
        }
        return out;
    }
}
