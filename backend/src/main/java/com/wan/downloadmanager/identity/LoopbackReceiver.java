package com.wan.downloadmanager.identity;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * One-shot HTTP listener on 127.0.0.1 with an ephemeral port for the OAuth redirect (RFC 8252 §7.3).
 * It accepts exactly one request on the redirect path and then stops.
 */
public class LoopbackReceiver implements AutoCloseable {
    private final HttpServer server;
    private final String path;
    private final CompletableFuture<Map<String, String>> result = new CompletableFuture<>();

    public LoopbackReceiver(String path) throws IOException {
        this.path = path;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4);
        server.createContext(path, ex -> {
            try (ex) {
                if (!ex.getRequestURI().getPath().equals(path) || !"GET".equals(ex.getRequestMethod())) {
                    ex.sendResponseHeaders(404, -1);
                    return;
                }
                Map<String, String> params = parse(ex.getRequestURI().getRawQuery());
                boolean ok = params.containsKey("code") && !params.containsKey("error");
                byte[] page = page(ok).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                ex.getResponseHeaders().add("Cache-Control", "no-store");
                ex.getResponseHeaders().add("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
                ex.sendResponseHeaders(200, page.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(page);
                }
                result.complete(params);
            }
        });
        server.start();
    }

    public String redirectUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    public CompletableFuture<Map<String, String>> result() {
        return result;
    }

    @Override
    public void close() {
        server.stop(0);
        result.cancel(false);
    }

    private static Map<String, String> parse(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            m.putIfAbsent(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String page(boolean ok) {
        String title = ok ? "Signed in to WAN Download Manager" : "Sign-in did not complete";
        String body = ok ? "You can close this tab and return to WAN Download Manager."
                : "Return to WAN Download Manager and try again.";
        return "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>" + title + "</title>"
                + "<body style=\"font-family:Segoe UI,system-ui,sans-serif;background:#0b1222;color:#e7edf8;display:grid;place-items:center;min-height:100vh;margin:0\">"
                + "<main style=\"background:#121b2f;border:1px solid #23314d;border-radius:20px;padding:32px 40px;max-width:28rem;text-align:center\">"
                + "<h1 style=\"font-size:20px;margin:0 0 8px\">" + title + "</h1><p style=\"color:#8d9bb5;margin:0\">" + body + "</p></main></body></html>";
    }
}
