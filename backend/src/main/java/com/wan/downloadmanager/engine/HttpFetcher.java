package com.wan.downloadmanager.engine;

import com.wan.downloadmanager.exception.WdmException;
import com.wan.downloadmanager.security.SafeFiles;
import com.wan.downloadmanager.security.UrlPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP access for the engine. Redirects are followed manually (max 10 hops) so that every hop is
 * re-validated by {@link UrlPolicy} (SSRF protection) and https→http downgrades are refused.
 */
public class HttpFetcher {
    public static final String USER_AGENT = "WAN-Download-Manager/0.1 (+https://wan.example/download-manager)";
    private static final int MAX_REDIRECTS = 10;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);

    private final HttpClient client;
    private final UrlPolicy policy;
    private final Duration readTimeout;

    public HttpFetcher(UrlPolicy policy) {
        this(policy, Duration.ofSeconds(30));
    }

    public HttpFetcher(UrlPolicy policy, Duration readTimeout) {
        this.policy = policy;
        this.readTimeout = readTimeout;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(20))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** What the server says about a resource, learned with a one-byte ranged GET. */
    public record Probe(URI finalUri, int status, Long totalBytes, boolean rangeSupported, String etag,
                       String lastModified, String contentType, String fileName) {
        /** Validator to send with If-Range; only strong ETags qualify (RFC 9110 §13.1.5). */
        public String ifRangeValidator() {
            if (etag != null && !etag.startsWith("W/")) return etag;
            return lastModified;
        }
    }

    public Probe probe(URI uri, Map<String, String> headers) throws IOException, InterruptedException {
        HttpResponse<InputStream> res = send(uri, headers, "bytes=0-0", null);
        try (InputStream body = res.body()) {
            URI finalUri = res.request().uri();
            int status = res.statusCode();
            if (status >= 400) throw new HttpStatusException(status);
            var h = res.headers();
            String etag = h.firstValue("ETag").orElse(null);
            String lastModified = h.firstValue("Last-Modified").orElse(null);
            String type = h.firstValue("Content-Type").map(t -> t.split(";")[0].trim()).orElse(null);
            String name = SafeFiles.fileNameFrom(h.firstValue("Content-Disposition").orElse(null), finalUri);
            Long total = null;
            boolean ranges = false;
            if (status == 206) {
                Matcher m = CONTENT_RANGE.matcher(h.firstValue("Content-Range").orElse(""));
                if (m.find() && !m.group(3).equals("*")) {
                    total = Long.parseLong(m.group(3));
                    ranges = true;
                }
            } else {
                OptionalLong len = h.firstValueAsLong("Content-Length");
                if (len.isPresent() && len.getAsLong() >= 0 && !h.firstValue("Content-Encoding").isPresent()) total = len.getAsLong();
            }
            return new Probe(finalUri, status, total, ranges, etag, lastModified, type, name);
        }
    }

    /**
     * Opens a body stream. With {@code start > 0} or an end, a 206 is required; a 200 means the server
     * ignored the range — with If-Range that signals the resource changed ({@link SourceChangedException}).
     */
    public HttpResponse<InputStream> open(URI uri, Map<String, String> headers, long start, Long endInclusive, String ifRange)
            throws IOException, InterruptedException {
        boolean ranged = start > 0 || endInclusive != null;
        String range = ranged ? "bytes=" + start + "-" + (endInclusive == null ? "" : endInclusive) : null;
        HttpResponse<InputStream> res = send(uri, headers, range, ranged ? ifRange : null);
        int status = res.statusCode();
        if (status >= 400) {
            res.body().close();
            throw new HttpStatusException(status);
        }
        if (ranged && status == 200) {
            res.body().close();
            if (ifRange != null) throw new SourceChangedException("Server returned the full resource for If-Range");
            throw new RangeNotHonouredException();
        }
        if (ranged && status == 206) {
            Matcher m = CONTENT_RANGE.matcher(res.headers().firstValue("Content-Range").orElse(""));
            if (!m.find() || Long.parseLong(m.group(1)) != start) {
                res.body().close();
                throw new IOException("Server answered a different range than requested");
            }
        }
        return res;
    }

    private HttpResponse<InputStream> send(URI uri, Map<String, String> headers, String range, String ifRange)
            throws IOException, InterruptedException {
        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            policy.checkHost(current.getHost());
            HttpRequest.Builder b = HttpRequest.newBuilder(current).GET().timeout(readTimeout)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Encoding", "identity");
            headers.forEach(b::header);
            if (range != null) b.header("Range", range);
            if (ifRange != null) b.header("If-Range", ifRange);
            HttpResponse<InputStream> res = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            int s = res.statusCode();
            if (s == 301 || s == 302 || s == 303 || s == 307 || s == 308) {
                res.body().close();
                Optional<String> loc = res.headers().firstValue("Location");
                if (loc.isEmpty()) throw new IOException("Redirect without Location");
                URI next = current.resolve(loc.get().trim());
                if ("https".equalsIgnoreCase(current.getScheme()) && "http".equalsIgnoreCase(next.getScheme())) {
                    throw WdmException.forbidden("redirect_downgrade", "Refused redirect from https to http");
                }
                current = policy.parse(next.toString());
                continue;
            }
            return res;
        }
        throw new IOException("Too many redirects");
    }

    /** Non-success HTTP status. 408, 429 and 5xx are worth retrying. */
    public static class HttpStatusException extends IOException {
        private final int status;
        public HttpStatusException(int status) {
            super("HTTP " + status);
            this.status = status;
        }
        public int status() { return status; }
        public boolean retryable() { return status == 408 || status == 429 || status >= 500; }
    }

    /** The resource behind the URL is not the one we started with (ETag/Last-Modified changed). */
    public static class SourceChangedException extends IOException {
        public SourceChangedException(String msg) { super(msg); }
    }

    /** The server ignored a Range header without If-Range (no resume possible). */
    public static class RangeNotHonouredException extends IOException {
        public RangeNotHonouredException() { super("Server does not honour byte ranges"); }
    }

    static List<String> sensitiveHeaders() {
        return List.of("cookie", "authorization", "proxy-authorization", "host", "range", "if-range", "content-length", "connection");
    }
}
