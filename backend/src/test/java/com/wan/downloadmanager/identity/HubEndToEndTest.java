package com.wan.downloadmanager.identity;

import com.wan.downloadmanager.entitlement.EntitlementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Against a REAL WAN Identity Hub with docs/integrations/wan-identity-hub-desktop-client.patch applied
 * (development mode, demo account). Opt-in:
 * <pre>WAN_HUB_ISSUER=http://localhost:4100/oidc WAN_HUB_PSQL=postgresql://postgres:postgres@localhost:5432/wan_identity mvn -pl backend test -Dtest=HubEndToEndTest</pre>
 * The "browser" signs in to the hub's own login API (as the hub's web page would), then follows the
 * authorization redirects to the app's loopback listener.
 */
@EnabledIfEnvironmentVariable(named = "WAN_HUB_ISSUER", matches = "https?://.+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class HubEndToEndTest {
    static final String ISSUER = System.getenv("WAN_HUB_ISSUER");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("wdm-hub-e2e").toString();
        r.add("wan.data-dir", () -> dir);
        r.add("wan.api-port", () -> 0);
        r.add("wan.identity.issuer", () -> ISSUER);
        r.add("wan.identity.client-id", () -> "wan-download-manager");
        r.add("wan.identity.login-timeout-seconds", () -> 20);
    }

    @MockitoBean BrowserLauncher browser;
    @Autowired IdentityService identity;
    @Autowired EntitlementService entitlements;
    @Autowired CredentialVault vault;

    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
    final String hub = ISSUER.replaceAll("/oidc$", "");

    void hubLogin() throws Exception {
        http.send(HttpRequest.newBuilder(URI.create(hub + "/api/auth/session")).build(), HttpResponse.BodyHandlers.discarding());
        String csrf = cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("csrf_token"))
                .map(HttpCookie::getValue).findFirst().orElseThrow();
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(hub + "/api/auth/login"))
                .header("Content-Type", "application/json").header("X-CSRF-Token", csrf)
                .POST(HttpRequest.BodyPublishers.ofString("{\"method\":\"email\",\"identifier\":\"demo@wan.dev\",\"password\":\"Demo@1234\",\"remember\":false}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    }

    void follow(URI start, AtomicReference<String> error) {
        try {
            URI current = start;
            for (int i = 0; i < 15; i++) {
                HttpResponse<String> res = http.send(HttpRequest.newBuilder(current).build(), HttpResponse.BodyHandlers.ofString());
                String loc = res.headers().firstValue("Location").orElse(null);
                if (current.getHost().equals("127.0.0.1") && current.getPath().equals("/callback")) return;
                if (loc == null) {
                    error.set("Stopped at " + current + " with HTTP " + res.statusCode() + ": " + res.body());
                    return;
                }
                current = current.resolve(loc);
            }
            error.set("Too many redirects");
        } catch (Exception e) {
            error.set(e.toString());
        }
    }

    IdentityService.Status await() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (identity.status() == IdentityService.Status.SIGNING_IN && System.currentTimeMillis() < deadline) Thread.sleep(50);
        return identity.status();
    }

    void psql(String sql) throws Exception {
        Process p = new ProcessBuilder("psql", System.getenv("WAN_HUB_PSQL"), "-v", "ON_ERROR_STOP=1", "-c", sql).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(out).isZero();
    }

    @Test
    void desktopSignInAgainstTheRealHub() throws Exception {
        hubLogin();
        AtomicReference<String> browseError = new AtomicReference<>();
        doAnswer(inv -> {
            URI u = inv.getArgument(0);
            Thread.ofVirtual().start(() -> follow(u, browseError));
            return null;
        }).when(browser).open(any());

        identity.startLogin(false);
        assertThat(await()).as("last error: " + identity.account().lastError() + " / browser: " + browseError.get())
                .isEqualTo(IdentityService.Status.SIGNED_IN);
        var acct = identity.account();
        assertThat(acct.profile().email()).isEqualTo("demo@wan.dev");
        assertThat(acct.entitlement().hubStatus()).isEqualTo("active");
        assertThat(acct.entitlement().roles()).containsExactly("free");
        assertThat(acct.entitlement().tier()).isEqualTo(EntitlementService.Tier.FREE);

        // The platform owner upgrades the person (what the Platform API / admin would record).
        if (System.getenv("WAN_HUB_PSQL") != null) {
            psql("update entitlements set roles = '{pro}' where platform_id = 'wan-download-manager' and user_id = '" + acct.profile().sub() + "'");
            identity.reverify(); // real refresh_token grant at the hub
            assertThat(entitlements.state().tier()).isEqualTo(EntitlementService.Tier.PRO);
            psql("update entitlements set roles = '{free}' where platform_id = 'wan-download-manager' and user_id = '" + acct.profile().sub() + "'");
        }

        String refresh = vault.get(IdentityService.VAULT_REFRESH).orElseThrow();
        var out = identity.logout();
        assertThat(out.revoked()).isTrue();
        // The revoked refresh token no longer works at the hub.
        var client = new OidcClient(ISSUER, "wan-download-manager", new com.fasterxml.jackson.databind.ObjectMapper());
        try {
            client.refresh(refresh);
            throw new AssertionError("revoked refresh token was accepted");
        } catch (OidcClient.OidcRejected e) {
            assertThat(e.error()).isEqualTo("invalid_grant");
        }
    }
}
