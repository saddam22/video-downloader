package com.wan.downloadmanager;

import com.wan.downloadmanager.config.WanProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Embedded backend of WAN Download Manager. The desktop app starts it in-process; it can also run
 * headless (e.g. for development) with {@code mvn -pl backend spring-boot:run}.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(WanProperties.class)
public class WanDownloadManagerBackend {
    public static void main(String[] args) {
        SpringApplication.run(WanDownloadManagerBackend.class, args);
    }
}
