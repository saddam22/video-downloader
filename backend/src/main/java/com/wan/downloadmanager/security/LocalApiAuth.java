package com.wan.downloadmanager.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wan.downloadmanager.config.FilePermissions;
import com.wan.downloadmanager.config.WanProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-run random bearer token for the localhost API. It is written, with the bound port, to
 * {@code runtime.json} in the user's data folder (owner-only) so that the native messaging host —
 * started by the browser as the same user — can reach the API. Nothing else learns the token.
 */
@Component
public class LocalApiAuth {
    private static final Logger log = LoggerFactory.getLogger(LocalApiAuth.class);

    private final String token;
    private final Path runtimeFile;
    private final ObjectMapper mapper;
    private volatile int port;

    public LocalApiAuth(WanProperties props, ObjectMapper mapper) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        this.runtimeFile = Path.of(props.dataDir(), "runtime.json");
        this.mapper = mapper;
    }

    public String token() {
        return token;
    }

    public int port() {
        return port;
    }

    /** Constant-time comparison of a presented token. */
    public boolean matches(String presented) {
        if (presented == null) return false;
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    @EventListener
    public void onServerStarted(WebServerInitializedEvent event) throws IOException {
        this.port = event.getWebServer().getPort();
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("port", port);
        runtime.put("token", token);
        runtime.put("pid", ProcessHandle.current().pid());
        Path tmp = runtimeFile.resolveSibling("runtime.json.tmp");
        Files.writeString(tmp, mapper.writeValueAsString(runtime));
        FilePermissions.restrictToOwner(tmp);
        Files.move(tmp, runtimeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        log.info("Local API listening on 127.0.0.1:{}", port);
    }

    @PreDestroy
    void cleanup() {
        try {
            Files.deleteIfExists(runtimeFile);
        } catch (IOException ignored) {
            // best effort; a stale file only holds a dead port and a token that no longer works
        }
    }
}
