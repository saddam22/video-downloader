package com.wan.downloadmanager;

import com.wan.downloadmanager.repository.DownloadCategoryRepository;
import com.wan.downloadmanager.repository.DownloadQueueRepository;
import com.wan.downloadmanager.security.LocalApiAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationContextTest extends BackendTestBase {
    @Autowired DownloadCategoryRepository categories;
    @Autowired DownloadQueueRepository queues;
    @Autowired LocalApiAuth auth;

    @Test
    void migratesSchemaAndSeeds() {
        assertThat(categories.findById("cat-video")).isPresent();
        assertThat(categories.findById("cat-other")).isPresent();
        assertThat(queues.findById("queue-main")).isPresent();
    }

    @Test
    void writesRuntimeFileWithPortAndToken() throws Exception {
        Path runtime = Path.of(dataDir.toString(), "runtime.json");
        assertThat(runtime).exists();
        assertThat(Files.readString(runtime)).contains("\"port\":" + auth.port()).contains(auth.token());
    }

    @Test
    void localApiRequiresTokenAndRefusesBrowserOrigins() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        URI uri = URI.create("http://127.0.0.1:" + auth.port() + "/api/v1/settings");
        assertThat(http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer wrong").build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + auth.token())
                .header("Origin", "https://evil.example").build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        HttpResponse<String> ok = http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + auth.token()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("\"connections\":8");
    }
}
