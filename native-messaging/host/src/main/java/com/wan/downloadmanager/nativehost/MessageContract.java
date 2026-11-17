package com.wan.downloadmanager.nativehost;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Strict JSON contract for extension → host messages (mirrors browser-extensions/shared/src/contract.ts).
 * Unknown fields are refused, every string is length-limited, URLs must be http(s) or blob: (media only).
 */
public final class MessageContract {
    private MessageContract() {}

    private static final Set<String> COMMON = Set.of("type", "id", "v");
    private static final Set<String> HELLO = Set.of("version");
    private static final Set<String> DOWNLOAD = Set.of("url", "fileName", "pageUrl", "mimeType");
    private static final Set<String> MEDIA = Set.of("pageUrl", "title", "sources");
    private static final Set<String> MEDIA_DOWNLOAD = Set.of("sourceId", "formatId");
    private static final java.util.regex.Pattern UUID = java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Set<String> SOURCE = Set.of("url", "mimeType", "width", "height", "durationSec", "poster");

    /** Returns null when valid, otherwise a short reason. */
    public static String validate(JsonNode m) {
        if (m == null || !m.isObject()) return "message must be an object";
        if (!m.path("type").isTextual()) return "type required";
        String type = m.get("type").asText();
        if (!NativeHost.TYPES.contains(type)) return "unknown type";
        if (m.has("id") && (!m.get("id").isTextual() || m.get("id").asText().length() > 64)) return "bad id";
        if (m.has("v") && m.get("v").asInt(-1) != 1) return "unsupported protocol version";
        Set<String> allowed = switch (type) {
            case "hello" -> HELLO;
            case "download" -> DOWNLOAD;
            case "media" -> MEDIA;
            case "media-download" -> MEDIA_DOWNLOAD;
            default -> Set.of();
        };
        for (Iterator<String> it = m.fieldNames(); it.hasNext(); ) {
            String f = it.next();
            if (!COMMON.contains(f) && !allowed.contains(f)) return "unexpected field " + f;
        }
        switch (type) {
            case "hello" -> {
                return optText(m, "version", 32);
            }
            case "download" -> {
                String r = url(m, "url", true, false);
                if (r != null) return r;
                r = optText(m, "fileName", 255);
                if (r != null) return r;
                r = url(m, "pageUrl", false, false);
                if (r != null) return r;
                return optText(m, "mimeType", 100);
            }
            case "media" -> {
                String r = url(m, "pageUrl", true, false);
                if (r != null) return r;
                r = optText(m, "title", 500);
                if (r != null) return r;
                JsonNode s = m.get("sources");
                if (s == null || !s.isArray() || s.isEmpty() || s.size() > 50) return "sources must be 1–50 items";
                for (JsonNode src : s) {
                    if (!src.isObject()) return "source must be an object";
                    for (Iterator<String> it = src.fieldNames(); it.hasNext(); ) {
                        if (!SOURCE.contains(it.next())) return "unexpected source field";
                    }
                    r = url(src, "url", true, true);
                    if (r == null) r = optText(src, "mimeType", 100);
                    if (r == null) r = url(src, "poster", false, false);
                    if (r == null) r = optNumber(src, "width", 1, 16384);
                    if (r == null) r = optNumber(src, "height", 1, 16384);
                    if (r == null) r = optNumber(src, "durationSec", 0, 1e7);
                    if (r != null) return r;
                }
                return null;
            }
            case "media-download" -> {
                for (String f : MEDIA_DOWNLOAD) {
                    if (!m.path(f).isTextual() || !UUID.matcher(m.get(f).asText()).matches()) return f + " must be an id";
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private static String optText(JsonNode m, String f, int max) {
        if (!m.has(f) || m.get(f).isNull()) return null;
        if (!m.get(f).isTextual()) return f + " must be text";
        String v = m.get(f).asText();
        if (v.length() > max) return f + " too long";
        if (v.chars().anyMatch(c -> c < 0x20)) return f + " has control characters";
        return null;
    }

    private static String optNumber(JsonNode m, String f, double min, double max) {
        if (!m.has(f) || m.get(f).isNull()) return null;
        if (!m.get(f).isNumber()) return f + " must be a number";
        double v = m.get(f).asDouble();
        return v < min || v > max || Double.isNaN(v) ? f + " out of range" : null;
    }

    private static String url(JsonNode m, String f, boolean required, boolean allowBlob) {
        if (!m.has(f) || m.get(f).isNull()) return required ? f + " required" : null;
        String r = optText(m, f, 8192);
        if (r != null) return r;
        String v = m.get(f).asText();
        if (allowBlob && v.startsWith("blob:")) return null;
        try {
            URI u = new URI(v);
            String s = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!s.equals("http") && !s.equals("https")) return f + " must be http(s)";
            if (u.getHost() == null) return f + " has no host";
        } catch (Exception e) {
            return f + " is not a URL";
        }
        return null;
    }
}
