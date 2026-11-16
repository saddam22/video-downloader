package com.wan.downloadmanager;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Full backend on a random localhost port with a throw-away data directory. Loopback downloads allowed for mock servers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
public abstract class BackendTestBase {
    protected static final Path dataDir;
    static {
        try {
            dataDir = Files.createTempDirectory("wdm-test");
            System.setProperty("wan.default-download-dir", Files.createTempDirectory("wdm-downloads").toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("wan.data-dir", dataDir::toString);
        r.add("wan.api-port", () -> 0);
        r.add("wan.security.allow-loopback-downloads", () -> true);
    }
}
