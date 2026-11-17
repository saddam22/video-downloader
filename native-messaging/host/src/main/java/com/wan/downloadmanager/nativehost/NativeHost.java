package com.wan.downloadmanager.nativehost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Native messaging host started by Chrome, Edge or Firefox (one process per extension connection).
 * Protocol: 4-byte native-endian length + UTF-8 JSON, both directions. Every message is validated
 * against a strict contract before anything is forwarded to the app's localhost API; the API token is
 * read from the user's runtime.json and never sent to the extension.
 *
 * <p>The browser passes the caller's origin as the first argument (Chrome/Edge:
 * {@code chrome-extension://<id>/}; Firefox: the path of the app manifest and the extension id). Only
 * origins listed in the host manifest can start the host at all; the id is checked again here.
 */
public final class NativeHost {
    public static final int MAX_IN = 1024 * 1024;      // browsers allow 4 GiB in, we accept 1 MiB
    public static final int MAX_OUT = 1024 * 1024;     // browsers accept at most 1 MiB out
    static final Set<String> TYPES = Set.of("hello", "status", "download", "media", "media-download", "ping");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path runtimeFile;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Set<String> allowedOrigins;

    public NativeHost(Path runtimeFile, Set<String> allowedOrigins) {
        this.runtimeFile = runtimeFile;
        this.allowedOrigins = allowedOrigins;
    }

    public static void main(String[] args) throws IOException {
        String origin = args.length > 0 ? args[0] : "";
        String firefoxId = args.length > 1 ? args[1] : "";
        NativeHost host = new NativeHost(dataDir().resolve("runtime.json"), AllowedOrigins.load());
        String caller = origin.startsWith("chrome-extension://") ? origin : firefoxId;
        if (!host.allowedOrigins.contains(caller)) {
            host.write(System.out, host.error("origin_not_allowed", "This extension is not allowed to use WAN Download Manager"));
            return;
        }
        host.loop(System.in, System.out, browserOf(origin));
    }

    static String browserOf(String origin) {
        if (origin.startsWith("chrome-extension://")) {
            return AllowedOrigins.EDGE_IDS.stream().anyMatch(origin::contains) ? "EDGE" : "CHROME";
        }
        return "FIREFOX";
    }

    void loop(InputStream in, OutputStream out, String browser) throws IOException {
        while (true) {
            byte[] msg;
            try {
                msg = read(in);
            } catch (EOFException e) {
                return; // browser closed the port
            } catch (IOException e) {
                write(out, error("bad_message", e.getMessage()));
                return; // framing is broken: stop rather than guess
            }
            write(out, handle(msg, browser));
        }
    }

    static byte[] read(InputStream in) throws IOException {
        byte[] len = in.readNBytes(4);
        if (len.length == 0) throw new EOFException();
        if (len.length < 4) throw new IOException("truncated length");
        int n = ByteBuffer.wrap(len).order(ByteOrder.nativeOrder()).getInt();
        if (n <= 0 || n > MAX_IN) throw new IOException("message size " + n + " not accepted");
        byte[] body = in.readNBytes(n);
        if (body.length != n) throw new IOException("truncated message");
        return body;
    }

    void write(OutputStream out, JsonNode msg) throws IOException {
        byte[] b = mapper.writeValueAsBytes(msg);
        if (b.length > MAX_OUT) b = mapper.writeValueAsBytes(error("too_large", "Response too large"));
        out.write(ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(b.length).array());
        out.write(b);
        out.flush();
    }

    JsonNode handle(byte[] raw, String browser) {
        JsonNode m;
        try {
            m = mapper.readTree(raw);
        } catch (IOException e) {
            return error("bad_json", "Message is not JSON");
        }
        String invalid = MessageContract.validate(m);
        if (invalid != null) return error("invalid_message", invalid);
        String type = m.get("type").asText();
        String id = m.path("id").asText("");
        try {
            JsonNode res = switch (type) {
                case "ping" -> mapper.createObjectNode().put("pong", true);
                case "hello" -> api("POST", "/api/v1/browser/connect", mapper.createObjectNode()
                        .put("browser", browser).put("extensionVersion", m.path("version").asText(null)));
                case "status" -> api("GET", "/api/v1/browser/status", null);
                case "download" -> api("POST", "/api/v1/browser/download", mapper.createObjectNode()
                        .put("url", m.get("url").asText()).put("fileName", m.path("fileName").asText(null))
                        .put("pageUrl", m.path("pageUrl").asText(null)).put("mimeType", m.path("mimeType").asText(null))
                        .put("browser", browser));
                case "media" -> {
                    ObjectNode body = mapper.createObjectNode().put("pageUrl", m.get("pageUrl").asText())
                            .put("title", m.path("title").asText(null)).put("browser", browser);
                    body.set("sources", m.get("sources"));
                    yield api("POST", "/api/v1/browser/media", body);
                }
                case "media-download" -> api("POST", "/api/v1/media/sources/" + m.get("sourceId").asText() + "/download",
                        mapper.createObjectNode().put("formatId", m.get("formatId").asText()));
                default -> error("invalid_message", "unknown type");
            };
            return envelope(id, res);
        } catch (AppNotRunning e) {
            return envelope(id, error("app_not_running", "WAN Download Manager is not running. Start it and try again."));
        } catch (Exception e) {
            return envelope(id, error("host_error", e.getClass().getSimpleName()));
        }
    }

    private JsonNode envelope(String id, JsonNode result) {
        ObjectNode o = mapper.createObjectNode();
        o.put("id", id);
        if (result.has("error")) o.set("error", result.get("error"));
        else o.set("result", result);
        return o;
    }

    static final class AppNotRunning extends Exception {}

    private JsonNode api(String method, String path, JsonNode body) throws Exception {
        if (!Files.exists(runtimeFile)) throw new AppNotRunning();
        JsonNode rt = mapper.readTree(runtimeFile.toFile());
        int port = rt.path("port").asInt(0);
        String token = rt.path("token").asText("");
        if (port <= 0 || port > 65535 || token.isEmpty()) throw new AppNotRunning();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));
        HttpResponse<byte[]> res;
        try {
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.net.ConnectException e) {
            throw new AppNotRunning();
        }
        JsonNode parsed = res.body().length == 0 ? mapper.createObjectNode() : mapper.readTree(res.body());
        if (res.statusCode() >= 400 && !parsed.has("error")) return error("http_" + res.statusCode(), "Request failed");
        if (parsed.isArray()) {
            ObjectNode wrap = mapper.createObjectNode();
            wrap.set("items", parsed);
            return wrap;
        }
        return parsed;
    }

    JsonNode error(String code, String message) {
        ObjectNode o = mapper.createObjectNode();
        o.putObject("error").put("code", code).put("message", message);
        return o;
    }

    static Path dataDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String override = System.getenv("WAN_DATA_DIR");
        if (override != null && !override.isBlank()) return Path.of(override);
        if (os.contains("win") && System.getenv("LOCALAPPDATA") != null) return Path.of(System.getenv("LOCALAPPDATA"), "WAN", "DownloadManager");
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isBlank()) return Path.of(xdg, "wan-download-manager");
        return Path.of(System.getProperty("user.home"), ".local", "share", "wan-download-manager");
    }
}
