package com.wan.downloadmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wan.downloadmanager.engine.MockHttpServer;
import com.wan.downloadmanager.entitlement.EntitlementService;
import com.wan.downloadmanager.security.LocalApiAuth;
import com.wan.downloadmanager.storage.SettingsService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** The local REST API end to end: real Spring context, real SQLite, real downloads from a local server. */
class DownloadApiTest extends BackendTestBase {
    static MockHttpServer files;
    @Autowired LocalApiAuth auth;
    @Autowired ObjectMapper mapper;
    @Autowired EntitlementService entitlements;
    @Autowired SettingsService settings;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void startServer() throws Exception {
        files = new MockHttpServer();
    }

    @AfterAll
    static void stopServer() {
        files.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        var s = SettingsService.defaults();
        settings.update(new com.wan.downloadmanager.storage.Settings(s.theme(), s.language(), false, false, true, true,
                Files.createTempDirectory("wdm-dl").toString(), 8, 3, 0, 3, true, true,
                true, true, true, true, true, List.of(), "", "", "mp4", s.mediaOutputDir()));
    }

    @AfterEach
    void free() {
        entitlements.apply(EntitlementService.State.free(EntitlementService.Source.SIGNED_OUT));
    }

    JsonNode call(String method, String path, Object body, int expect) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + auth.port() + path))
                .header("Authorization", "Bearer " + auth.token()).header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as(method + " " + path + " → " + res.body()).isEqualTo(expect);
        return res.body().isEmpty() ? null : mapper.readTree(res.body());
    }

    JsonNode waitFor(String id, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        JsonNode d;
        do {
            d = call("GET", "/api/v1/downloads/" + id, null, 200);
            if (d.get("status").asText().equals(status)) return d;
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("Download " + id + " never reached " + status + ": " + d);
    }

    static byte[] random(int n) {
        byte[] b = new byte[n];
        new Random(n).nextBytes(b);
        return b;
    }

    record Create(String url, String fileName, Integer connections, Boolean allowDuplicate, java.util.Map<String, String> headers) {}

    @Test
    void freeTierDownloadsWithOneConnectionAndCategorises() throws Exception {
        byte[] data = random(2 * 1024 * 1024);
        files.put("/docs/report.pdf", data);
        JsonNode created = call("POST", "/api/v1/downloads", new Create(files.url("/docs/report.pdf"), null, 8, null, null), 201);
        assertThat(created.get("connections").asInt()).as("Free plan: one connection").isEqualTo(1);
        JsonNode done = waitFor(created.get("id").asText(), "COMPLETED");
        Path file = Path.of(done.get("filePath").asText());
        assertThat(file.getParent().getFileName().toString()).isEqualTo("Documents");
        assertThat(Files.readAllBytes(file)).isEqualTo(data);
        assertThat(done.get("progress").asDouble()).isEqualTo(1.0);

        JsonNode hist = call("GET", "/api/v1/history?q=report", null, 200);
        assertThat(hist.get(0).get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void proTierUsesParallelConnections() throws Exception {
        entitlements.apply(new EntitlementService.State(EntitlementService.Tier.PRO, EntitlementService.Source.ONLINE, "active", List.of("pro"), 0L, null));
        byte[] data = random(6 * 1024 * 1024);
        MockHttpServer.Resource r = files.put("/iso/disk.iso", data);
        JsonNode created = call("POST", "/api/v1/downloads", new Create(files.url("/iso/disk.iso"), null, 8, null, null), 201);
        assertThat(created.get("connections").asInt()).isEqualTo(8);
        JsonNode done = waitFor(created.get("id").asText(), "COMPLETED");
        assertThat(r.rangedRequests.get()).isGreaterThanOrEqualTo(8);
        assertThat(Files.readAllBytes(Path.of(done.get("filePath").asText()))).isEqualTo(data);
    }

    @Test
    void pauseResumeThroughTheApi() throws Exception {
        byte[] data = random(3 * 1024 * 1024);
        MockHttpServer.Resource r = files.put("/slow/video.mp4", data);
        r.delayPerChunkMs = 20;
        String id = call("POST", "/api/v1/downloads", new Create(files.url("/slow/video.mp4"), null, null, null, null), 201).get("id").asText();
        waitFor(id, "DOWNLOADING");
        Thread.sleep(700);
        call("POST", "/api/v1/downloads/" + id + "/pause", null, 200);
        JsonNode paused = waitFor(id, "PAUSED");
        assertThat(paused.get("downloadedBytes").asLong()).isPositive().isLessThan(data.length);
        r.delayPerChunkMs = 0;
        call("POST", "/api/v1/downloads/" + id + "/resume", null, 200);
        JsonNode done = waitFor(id, "COMPLETED");
        assertThat(Files.readAllBytes(Path.of(done.get("filePath").asText()))).isEqualTo(data);
        assertThat(r.rangedRequests.get()).as("resume used a byte range").isGreaterThanOrEqualTo(2);
    }

    @Test
    void duplicatesAreDetectedUnlessAllowed() throws Exception {
        files.put("/dup/a.zip", random(100_000));
        String url = files.url("/dup/a.zip");
        call("POST", "/api/v1/downloads", new Create(url, null, null, null, null), 201);
        JsonNode err = call("POST", "/api/v1/downloads", new Create(url, null, null, null, null), 409);
        assertThat(err.get("error").get("code").asText()).isEqualTo("duplicate");
        call("POST", "/api/v1/downloads", new Create(url, "a-copy.zip", null, true, null), 201);
    }

    @Test
    void refusesCredentialsHeadersAndBadUrls() throws Exception {
        call("POST", "/api/v1/downloads", new Create("ftp://example.com/x", null, null, null, null), 400);
        call("POST", "/api/v1/downloads", new Create("http://user:pw@example.com/x", null, null, null, null), 400);
        call("POST", "/api/v1/downloads", new Create("javascript:alert(1)", null, null, null, null), 400);
        call("POST", "/api/v1/downloads", new Create(files.url("/x.bin"), null, null, null, java.util.Map.of("Cookie", "a=b")), 400);
        call("POST", "/api/v1/downloads", new Create(files.url("/x.bin"), "../../evil.exe", null, null, null), 201)
                .get("fileName").asText().equals(".._.._evil.exe");
    }

    @Test
    void failedDownloadCanBeRetried() throws Exception {
        String id = call("POST", "/api/v1/downloads", new Create(files.url("/later/file.bin"), null, null, null, null), 201).get("id").asText();
        JsonNode failed = waitFor(id, "FAILED");
        assertThat(failed.get("errorMessage").asText()).contains("404");
        byte[] data = random(50_000);
        files.put("/later/file.bin", data);
        call("POST", "/api/v1/downloads/" + id + "/retry", null, 200);
        JsonNode done = waitFor(id, "COMPLETED");
        assertThat(Files.readAllBytes(Path.of(done.get("filePath").asText()))).isEqualTo(data);
    }

    @Test
    void cancelDeletesThePartialFile() throws Exception {
        MockHttpServer.Resource r = files.put("/cancel/big.bin", random(4 * 1024 * 1024));
        r.delayPerChunkMs = 30;
        JsonNode d = call("POST", "/api/v1/downloads", new Create(files.url("/cancel/big.bin"), null, null, null, null), 201);
        String id = d.get("id").asText();
        waitFor(id, "DOWNLOADING");
        Thread.sleep(300);
        call("POST", "/api/v1/downloads/" + id + "/cancel", null, 200);
        JsonNode cancelled = waitFor(id, "CANCELLED");
        assertThat(Path.of(cancelled.get("saveDir").asText(), id + ".wdmpart")).doesNotExist();
        call("DELETE", "/api/v1/downloads/" + id, null, 204);
        call("GET", "/api/v1/downloads/" + id, null, 404);
    }

    @Test
    void batchNeedsTheBatchFeatureAndDeduplicates() throws Exception {
        record Batch(String text, Boolean preview) {}
        JsonNode denied = call("POST", "/api/v1/downloads/batch", new Batch(files.url("/b/1.txt"), false), 402);
        assertThat(denied.get("error").get("code").asText()).isEqualTo("entitlement_required");
        JsonNode preview = call("POST", "/api/v1/downloads/batch", new Batch("# list\n" + files.url("/b/1.txt") + "\n" + files.url("/b/1.txt") + "\nnot a url\n", true), 200);
        assertThat(preview.get("duplicates").asInt()).isEqualTo(1);
        assertThat(preview.get("invalid").asInt()).isEqualTo(1);

        entitlements.apply(new EntitlementService.State(EntitlementService.Tier.PRO, EntitlementService.Source.ONLINE, "active", List.of("pro"), 0L, null));
        files.put("/b/1.txt", random(1000));
        files.put("/b/2.txt", random(2000));
        JsonNode res = call("POST", "/api/v1/downloads/batch", new Batch(files.url("/b/1.txt") + "\r\n" + files.url("/b/2.txt"), false), 200);
        assertThat(res.get("added").asInt()).isEqualTo(2);
        for (JsonNode line : res.get("lines")) waitFor(line.get("downloadId").asText(), "COMPLETED");
    }

    @Test
    void stoppedQueueHoldsDownloadsUntilStarted() throws Exception {
        record Q(String name, Integer priority, Integer maxConcurrent) {}
        record CreateInQueue(String url, String queueId) {}
        String q = call("POST", "/api/v1/queues", new Q("Night", 1, 2), 201).get("id").asText();
        files.put("/q/night.bin", random(80_000));
        String id = call("POST", "/api/v1/downloads", new CreateInQueue(files.url("/q/night.bin"), q), 201).get("id").asText();
        Thread.sleep(400);
        assertThat(call("GET", "/api/v1/downloads/" + id, null, 200).get("status").asText()).isEqualTo("QUEUED");
        call("POST", "/api/v1/queues/" + q + "/start", null, 200);
        waitFor(id, "COMPLETED");
    }

    @Test
    void historyExportIsCsvWithFormulaProtection() throws Exception {
        files.put("/csv/=cmd.txt", random(10));
        String id = call("POST", "/api/v1/downloads", new Create(files.url("/csv/=cmd.txt"), null, null, null, null), 201).get("id").asText();
        waitFor(id, "COMPLETED");
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + auth.port() + "/api/v1/history/export"))
                .header("Authorization", "Bearer " + auth.token()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        assertThat(res.body()).startsWith("file_name,").contains("\"'=cmd.txt\"");
    }
}
