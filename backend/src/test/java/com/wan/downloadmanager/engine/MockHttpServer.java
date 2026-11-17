package com.wan.downloadmanager.engine;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local HTTP server for engine tests: byte ranges, ETags, redirects, slow and broken responses. */
public class MockHttpServer implements AutoCloseable {
    public static final class Resource {
        public volatile byte[] data;
        public volatile String etag;
        public volatile boolean ranges = true;
        /** Close the connection after this many body bytes, for the next {@code failuresLeft} responses. */
        public volatile long dropAfter = -1;
        public final AtomicInteger failuresLeft = new AtomicInteger();
        /** Sleep this many ms per 16 KiB chunk (slow server for pause tests). */
        public volatile int delayPerChunkMs;
        public volatile String contentDisposition;
        public volatile String contentType = "application/octet-stream";
        public final AtomicInteger requests = new AtomicInteger();
        public final AtomicInteger rangedRequests = new AtomicInteger();

        public Resource(byte[] data, String etag) {
            this.data = data;
            this.etag = etag;
        }
    }

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d*)");
    private final HttpServer server;
    private final Map<String, Resource> resources = new ConcurrentHashMap<>();
    private final Map<String, String> redirects = new ConcurrentHashMap<>();

    public MockHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    public Resource put(String path, byte[] data) {
        Resource r = new Resource(data, "\"v1-" + data.length + "\"");
        resources.put(path, r);
        return r;
    }

    public void redirect(String from, String to) {
        redirects.put(from, to);
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            String to = redirects.get(path);
            if (to != null) {
                ex.getResponseHeaders().add("Location", to);
                ex.sendResponseHeaders(302, -1);
                return;
            }
            Resource r = resources.get(path);
            if (r == null) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            r.requests.incrementAndGet();
            byte[] data = r.data;
            long start = 0, end = data.length - 1;
            int status = 200;
            String range = ex.getRequestHeaders().getFirst("Range");
            String ifRange = ex.getRequestHeaders().getFirst("If-Range");
            if (r.ranges && range != null && (ifRange == null || ifRange.equals(r.etag))) {
                Matcher m = RANGE.matcher(range);
                if (m.matches()) {
                    r.rangedRequests.incrementAndGet();
                    start = Long.parseLong(m.group(1));
                    if (!m.group(2).isEmpty()) end = Math.min(Long.parseLong(m.group(2)), data.length - 1);
                    if (start >= data.length) {
                        ex.getResponseHeaders().add("Content-Range", "bytes */" + data.length);
                        ex.sendResponseHeaders(416, -1);
                        return;
                    }
                    status = 206;
                    ex.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + end + "/" + data.length);
                }
            }
            if (r.ranges) ex.getResponseHeaders().add("Accept-Ranges", "bytes");
            if (r.etag != null) ex.getResponseHeaders().add("ETag", r.etag);
            if (r.contentDisposition != null) ex.getResponseHeaders().add("Content-Disposition", r.contentDisposition);
            ex.getResponseHeaders().add("Content-Type", r.contentType);
            long len = end - start + 1;
            ex.sendResponseHeaders(status, len);
            boolean drop = r.dropAfter >= 0 && r.failuresLeft.getAndUpdate(v -> Math.max(0, v - 1)) > 0;
            OutputStream out = ex.getResponseBody();
            long sent = 0;
            int chunk = 16 * 1024;
            for (long off = start; off <= end; off += chunk) {
                int n = (int) Math.min(chunk, end - off + 1);
                if (drop && sent + n > r.dropAfter) {
                    out.write(data, (int) off, (int) Math.max(0, r.dropAfter - sent));
                    out.flush();
                    throw new IOException("simulated connection drop");
                }
                out.write(data, (int) off, n);
                sent += n;
                if (r.delayPerChunkMs > 0) {
                    out.flush();
                    Thread.sleep(r.delayPerChunkMs);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // client went away or simulated drop
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
