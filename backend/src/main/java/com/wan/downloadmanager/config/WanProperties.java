package com.wan.downloadmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed {@code wan.*} configuration. Identity values are empty unless an operator provides them. */
@ConfigurationProperties(prefix = "wan")
public record WanProperties(String dataDir, Identity identity, Licensing licensing, Billing billing, Security security) {

    public record Identity(String issuer, String clientId, String scopes, String redirectPath,
                           int loginTimeoutSeconds, String hubWebUrl) {
        public boolean configured() {
            return issuer != null && !issuer.isBlank() && clientId != null && !clientId.isBlank();
        }
    }

    public record Licensing(int offlineGraceDays, String platformId) {}

    public record Billing(String url) {}

    public record Security(boolean allowLoopbackDownloads, boolean allowPrivateNetworkDownloads) {}
}
