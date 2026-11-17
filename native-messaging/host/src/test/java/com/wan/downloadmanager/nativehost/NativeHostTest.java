package com.wan.downloadmanager.nativehost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class NativeHostTest {
    final ObjectMapper mapper = new ObjectMapper();

    static byte[] frame(String json) {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + b.length).order(ByteOrder.nativeOrder()).putInt(b.length).put(b).array();
    }

    List<JsonNode> replies(byte[] out) throws Exception {
        List<JsonNode> r = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.nativeOrder());
        while (buf.remaining() >= 4) {
            byte[] b = new byte[buf.getInt()];
            buf.get(b);
            r.add(mapper.readTree(b));
        }
        return r;
    }

    @Test
    void contractRefusesUnexpectedOrDangerousInput() throws Exception {
        assertThat(MessageContract.validate(mapper.readTree("{\"type\":\"download\",\"url\":\"https://e.com/a.zip\"}"))).isNull();
        assertThat(MessageContract.validate(mapper.readTree("{\"type\":\"download\",\"url\":\"file:///etc/passwd\"}"))).contains("http");
        assertThat(MessageContract.validate(mapper.readTree("{\"type\":\"download\",\"url\":\"https://e.com/\",\"cookie\":\"x\"}"))).contains("unexpected");
        assertThat(MessageContract.validate(mapper.readTree("{\"type\":\"exec\",\"cmd\":\"calc\"}"))).contains("unknown type");
        assertThat(MessageContract.validate(mapper.readTree("{\"type\":\"media\",\"pageUrl\":\"https://e.com/\",\"sources\":[]}"))).contains("1–50");
        assertThat(MessageContract.validate(mapper.readTree("{\"type\":\"media\",\"pageUrl\":\"https://e.com/\",\"sources\":[{\"url\":\"blob:https://e.com/1\",\"width\":99999}]}"))).contains("range");
        assertThat(MessageContract.validate(mapper.readTree("{\"type\":\"download\",\"url\":\"https://e.com/\",\"fileName\":\"a\\nb\"}"))).contains("control");
    }

    @Test
    void framingRejectsOversizedMessagesAndStops(@TempDir Path dir) throws Exception {
        NativeHost host = new NativeHost(dir.resolve("runtime.json"), Set.of());
        byte[] huge = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(NativeHost.MAX_IN + 1).array();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        host.loop(new ByteArrayInputStream(huge), out, "CHROME");
        assertThat(replies(out.toByteArray()).getFirst().path("error").path("code").asText()).isEqualTo("bad_message");
    }

    @Test
    void appNotRunningIsReportedClearly(@TempDir Path dir) throws Exception {
        NativeHost host = new NativeHost(dir.resolve("runtime.json"), Set.of());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        host.loop(new ByteArrayInputStream(frame("{\"type\":\"status\",\"id\":\"1\"}")), out, "CHROME");
        JsonNode r = replies(out.toByteArray()).getFirst();
        assertThat(r.path("id").asText()).isEqualTo("1");
        assertThat(r.path("error").path("code").asText()).isEqualTo("app_not_running");
    }

    @Test
    void forwardsValidMessagesWithTheTokenAndBrowserIdentity(@TempDir Path dir) throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        api.createContext("/api/v1/browser/download", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] res = "{\"id\":\"d1\",\"status\":\"QUEUED\"}".getBytes();
            ex.sendResponseHeaders(200, res.length);
            ex.getResponseBody().write(res);
            ex.close();
        });
        api.start();
        try {
            Path rt = dir.resolve("runtime.json");
            Files.writeString(rt, "{\"port\":" + api.getAddress().getPort() + ",\"token\":\"secret-token\"}");
            NativeHost host = new NativeHost(rt, Set.of());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] in = concat(frame("{\"type\":\"download\",\"id\":\"7\",\"url\":\"https://e.com/a.zip\",\"pageUrl\":\"https://e.com/\"}"),
                    frame("{\"type\":\"ping\",\"id\":\"8\"}"));
            host.loop(new ByteArrayInputStream(in), out, "FIREFOX");
            List<JsonNode> r = replies(out.toByteArray());
            assertThat(r).hasSize(2);
            assertThat(r.get(0).path("result").path("id").asText()).isEqualTo("d1");
            assertThat(r.get(0).toString()).doesNotContain("secret-token");
            assertThat(auth.get()).isEqualTo("Bearer secret-token");
            assertThat(body.get()).contains("\"browser\":\"FIREFOX\"").contains("https://e.com/a.zip");
            assertThat(r.get(1).path("result").path("pong").asBoolean()).isTrue();
        } finally {
            api.stop(0);
        }
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
