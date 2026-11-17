package com.wan.downloadmanager.engine;

import com.wan.downloadmanager.security.UrlPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadJobTest {
    @TempDir Path dir;
    MockHttpServer server;
    HttpFetcher fetcher;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockHttpServer();
        fetcher = new HttpFetcher(new UrlPolicy(true, true), Duration.ofSeconds(10));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    /** Captures what the job reports. */
    static class Recorder implements JobStore {
        final CountDownLatch done = new CountDownLatch(1);
        final List<String> retries = new CopyOnWriteArrayList<>();
        volatile String outcome;
        volatile String failure;
        volatile Path file;
        volatile String sha;
        volatile HttpFetcher.Probe probe;
        volatile boolean resumed;
        volatile List<PartState> parts = List.of();
        volatile long lastProgress;

        public void planned(HttpFetcher.Probe p, List<PartState> parts, boolean resumed) { this.probe = p; this.parts = parts; this.resumed = resumed; }
        public void progress(List<PartState> parts, long downloaded, long speed) { lastProgress = downloaded; }
        public void retrying(int attempt, String reason) { retries.add(reason); }
        public void paused(List<PartState> parts, long downloaded) { this.parts = parts; outcome = "PAUSED"; lastProgress = downloaded; done.countDown(); }
        public void failed(String reason, List<PartState> parts, long downloaded) { failure = reason; outcome = "FAILED"; done.countDown(); }
        public void completed(Path f, long size, String sha) { file = f; this.sha = sha; outcome = "COMPLETED"; done.countDown(); }

        void await() throws InterruptedException {
            assertThat(done.await(30, TimeUnit.SECONDS)).as("job finished").isTrue();
        }
    }

    static byte[] random(int size) {
        byte[] b = new byte[size];
        new Random(size).nextBytes(b);
        return b;
    }

    static String sha(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    DownloadJob job(String path, int connections, Recorder rec, List<PartState> existing, Long total, String etag, BandwidthLimiter... limiters) {
        Path temp = dir.resolve("file.bin.wdmpart");
        var spec = new DownloadJob.Spec("t1", URI.create(server.url(path)), Map.of(), dir, "file.bin", temp,
                connections, 4, 20, total, etag, null, existing);
        return new DownloadJob(spec, fetcher, rec, List.of(limiters), n -> {});
    }

    @Test
    void http200WithoutRangeSupportDownloadsInOneStream() throws Exception {
        byte[] data = random(3 * 1024 * 1024);
        server.put("/plain.bin", data).ranges = false;
        Recorder rec = new Recorder();
        job("/plain.bin", 8, rec, List.of(), null, null).run();
        rec.await();
        assertThat(rec.outcome).isEqualTo("COMPLETED");
        assertThat(rec.parts).hasSize(1);
        assertThat(rec.probe.rangeSupported()).isFalse();
        assertThat(Files.readAllBytes(rec.file)).isEqualTo(data);
        assertThat(rec.sha).isEqualTo(sha(data));
    }

    @Test
    void http206ParallelDownloadIsByteIdentical() throws Exception {
        byte[] data = random(10 * 1024 * 1024 + 123);
        MockHttpServer.Resource r = server.put("/big.bin", data);
        Recorder rec = new Recorder();
        job("/big.bin", 8, rec, List.of(), null, null).run();
        rec.await();
        assertThat(rec.outcome).isEqualTo("COMPLETED");
        assertThat(rec.parts).hasSize(8);
        assertThat(r.rangedRequests.get()).isGreaterThanOrEqualTo(8);
        assertThat(rec.sha).isEqualTo(sha(data));
        assertThat(Files.readAllBytes(rec.file)).isEqualTo(data);
        assertThat(rec.file.getFileName().toString()).isEqualTo("file.bin");
        assertThat(dir.resolve("file.bin.wdmpart")).doesNotExist();
    }

    @Test
    void http404FailsWithoutRetrying() throws Exception {
        Recorder rec = new Recorder();
        job("/missing", 4, rec, List.of(), null, null).run();
        rec.await();
        assertThat(rec.outcome).isEqualTo("FAILED");
        assertThat(rec.failure).contains("404");
        assertThat(rec.retries).isEmpty();
    }

    @Test
    void followsRedirects() throws Exception {
        byte[] data = random(2 * 1024 * 1024);
        server.put("/real.bin", data);
        server.redirect("/go", "/hop");
        server.redirect("/hop", server.url("/real.bin"));
        Recorder rec = new Recorder();
        job("/go", 4, rec, List.of(), null, null).run();
        rec.await();
        assertThat(rec.outcome).isEqualTo("COMPLETED");
        assertThat(rec.probe.finalUri().getPath()).isEqualTo("/real.bin");
        assertThat(Files.readAllBytes(rec.file)).isEqualTo(data);
    }

    @Test
    void networkInterruptionIsRetriedAndResumedWithinTheRange() throws Exception {
        byte[] data = random(4 * 1024 * 1024);
        MockHttpServer.Resource r = server.put("/flaky.bin", data);
        r.dropAfter = 300 * 1024;
        r.failuresLeft.set(3);
        Recorder rec = new Recorder();
        job("/flaky.bin", 2, rec, List.of(), null, null).run();
        rec.await();
        assertThat(rec.outcome).isEqualTo("COMPLETED");
        assertThat(rec.retries).isNotEmpty();
        assertThat(Files.readAllBytes(rec.file)).isEqualTo(data);
    }

    @Test
    void interruptionWithoutRangeSupportRestartsFromZero() throws Exception {
        byte[] data = random(1024 * 1024);
        MockHttpServer.Resource r = server.put("/norange.bin", data);
        r.ranges = false;
        r.dropAfter = 200 * 1024;
        r.failuresLeft.set(1);
        Recorder rec = new Recorder();
        job("/norange.bin", 4, rec, List.of(), null, null).run();
        rec.await();
        assertThat(rec.outcome).isEqualTo("COMPLETED");
        assertThat(Files.readAllBytes(rec.file)).isEqualTo(data);
    }

    @Test
    void persistentFailureGivesUpAfterMaxRetries() throws Exception {
        byte[] data = random(1024 * 1024);
        MockHttpServer.Resource r = server.put("/broken.bin", data);
        r.dropAfter = 0; // no byte ever arrives, so the retry counter never resets
        r.failuresLeft.set(1000);
        Recorder rec = new Recorder();
        job("/broken.bin", 1, rec, List.of(), null, null).run();
        rec.await();
        assertThat(rec.outcome).isEqualTo("FAILED");
        assertThat(rec.failure).contains("Gave up after 4 retries");
    }

    @Test
    void pauseThenResumeContinuesFromSavedOffsets() throws Exception {
        byte[] data = random(3 * 1024 * 1024);
        MockHttpServer.Resource r = server.put("/slow.bin", data);
        r.delayPerChunkMs = 15;
        Recorder first = new Recorder();
        DownloadJob j1 = job("/slow.bin", 4, first, List.of(), null, null);
        Thread t = Thread.ofVirtual().start(j1);
        while (first.lastProgress < 256 * 1024) Thread.sleep(20);
        j1.pause();
        first.await();
        t.join();
        assertThat(first.outcome).isEqualTo("PAUSED");
        long saved = first.parts.stream().mapToLong(PartState::downloaded).sum();
        assertThat(saved).isPositive().isLessThan(data.length);

        // Resume with copies of the persisted part state (as the service does after a restart).
        List<PartState> restored = new ArrayList<>();
        for (PartState p : first.parts) restored.add(new PartState(p.index(), p.start(), p.end(), p.downloaded(), PartState.Status.PENDING));
        r.delayPerChunkMs = 0;
        Recorder second = new Recorder();
        job("/slow.bin", 4, second, restored, (long) data.length, first.probe.etag()).run();
        second.await();
        assertThat(second.resumed).isTrue();
        assertThat(second.outcome).isEqualTo("COMPLETED");
        assertThat(Files.readAllBytes(second.file)).isEqualTo(data);
    }

    @Test
    void etagMismatchOnResumeRestartsWithTheNewContent() throws Exception {
        byte[] v1 = random(3 * 1024 * 1024);
        MockHttpServer.Resource r = server.put("/changing.bin", v1);
        r.delayPerChunkMs = 15;
        Recorder first = new Recorder();
        DownloadJob j1 = job("/changing.bin", 4, first, List.of(), null, null);
        Thread t = Thread.ofVirtual().start(j1);
        while (first.lastProgress < 256 * 1024) Thread.sleep(20);
        j1.pause();
        first.await();
        t.join();

        byte[] v2 = random(3 * 1024 * 1024 + 7);
        r.data = v2;
        r.etag = "\"v2\"";
        r.delayPerChunkMs = 0;
        List<PartState> restored = new ArrayList<>();
        for (PartState p : first.parts) restored.add(new PartState(p.index(), p.start(), p.end(), p.downloaded(), PartState.Status.PENDING));
        Recorder second = new Recorder();
        job("/changing.bin", 4, second, restored, (long) v1.length, first.probe.etag()).run();
        second.await();
        assertThat(second.resumed).as("old parts must not be reused").isFalse();
        assertThat(Files.readAllBytes(second.file)).isEqualTo(v2);
    }

    @Test
    void contentChangeMidDownloadIsDetectedByIfRange() throws Exception {
        byte[] v1 = random(4 * 1024 * 1024);
        MockHttpServer.Resource r = server.put("/swap.bin", v1);
        r.delayPerChunkMs = 10;
        // Every first connection breaks after 400 KiB, so each part must reconnect (with If-Range).
        r.dropAfter = 400 * 1024;
        r.failuresLeft.set(4);
        Recorder rec = new Recorder();
        DownloadJob j = job("/swap.bin", 4, rec, List.of(), null, null);
        Thread t = Thread.ofVirtual().start(j);
        while (r.requests.get() < 5) Thread.sleep(2); // probe + 4 part connections are streaming v1
        // Same size, new content: only the validator reveals it.
        r.data = java.util.Arrays.copyOf(random(4 * 1024 * 1024 + 1), v1.length);
        r.etag = "\"v2\"";
        r.delayPerChunkMs = 0;
        rec.await();
        t.join();
        assertThat(rec.outcome).isEqualTo("COMPLETED");
        assertThat(Files.readAllBytes(rec.file)).isEqualTo(r.data);
    }

    @Test
    void neverOverwritesAnExistingFile() throws Exception {
        byte[] data = random(600 * 1024);
        server.put("/dup.bin", data);
        Files.writeString(dir.resolve("file.bin"), "keep me");
        Recorder rec = new Recorder();
        job("/dup.bin", 1, rec, List.of(), null, null).run();
        rec.await();
        assertThat(rec.file.getFileName().toString()).isEqualTo("file (1).bin");
        assertThat(Files.readString(dir.resolve("file.bin"))).isEqualTo("keep me");
    }

    @Test
    void bandwidthLimitIsRespected() throws Exception {
        byte[] data = random(600 * 1024);
        server.put("/limited.bin", data);
        Recorder rec = new Recorder();
        long t0 = System.nanoTime();
        job("/limited.bin", 4, rec, List.of(), null, null, new BandwidthLimiter(400 * 1024)).run();
        rec.await();
        double secs = (System.nanoTime() - t0) / 1e9;
        assertThat(rec.outcome).isEqualTo("COMPLETED");
        // 600 KiB at 400 KiB/s with a 1-second burst allowance needs at least ~0.5 s.
        assertThat(secs).isGreaterThan(0.45);
    }
}
