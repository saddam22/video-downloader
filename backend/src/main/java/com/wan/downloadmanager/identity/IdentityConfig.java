package com.wan.downloadmanager.identity;

import com.wan.downloadmanager.config.WanProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;

@Configuration
public class IdentityConfig {

    @Bean
    @ConditionalOnMissingBean
    public CredentialVault credentialVault(WanProperties props) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return windows ? new WindowsDpapiVault(Path.of(props.dataDir())) : new InMemoryVault();
    }

    /** Opens the system browser. The desktop app replaces this with JavaFX HostServices. */
    @Bean
    @ConditionalOnMissingBean
    public BrowserLauncher browserLauncher() {
        return uri -> {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(uri.toString()));
            } else {
                throw new IllegalStateException("No system browser available; open this address manually: " + uri);
            }
        };
    }
}
