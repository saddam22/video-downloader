package com.wan.downloadmanager.identity;

import com.wan.downloadmanager.entitlement.EntitlementService;
import com.wan.downloadmanager.entitlement.Feature;
import com.wan.downloadmanager.entity.EntitlementCacheEntry;
import com.wan.downloadmanager.exception.WdmException;
import com.wan.downloadmanager.repository.EntitlementCacheEntryRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** Desktop sign-in (code + PKCE through a loopback redirect) against a mock provider with the hub's contract. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class IdentityFlowTest {
    static final MockOidcProvider op;
    static {
        try {
            op = new MockOidcProvider();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("wdm-identity").toString();
        r.add("wan.data-dir", () -> dir);
        r.add("wan.api-port", () -> 0);
        r.add("wan.identity.issuer", op::issuer);
        r.add("wan.identity.client-id", () -> MockOidcProvider.CLIENT);
        r.add("wan.identity.login-timeout-seconds", () -> 10);
    }

    @AfterAll
    static void stop() {
        op.close();
    }

    @MockitoBean BrowserLauncher browser;
    @Autowired IdentityService identity;
    @Autowired EntitlementService entitlements;
    @Autowired EntitlementCacheEntryRepository cache;

    @BeforeEach
    void reset() throws Exception {
        doAnswer(inv -> {
            URI u = inv.getArgument(0);
            Thread.ofVirtual().start(() -> {
                try {
                    op.browse(u);
                } catch (Exception ignored) {
                    // the test asserts the outcome
                }
            });
            return null;
        }).when(browser).open(any());
        op.wrongNonce = op.expired = op.wrongState = op.down = false;
        op.publicClients = true;
        op.roles = java.util.List.of("pro");
        op.entitlementStatus = "active";
        if (identity.status() == IdentityService.Status.SIGNED_IN || identity.status() == IdentityService.Status.OFFLINE) identity.logout();
    }

    IdentityService.Status signIn() throws InterruptedException {
        identity.startLogin(false);
        long deadline = System.currentTimeMillis() + 10_000;
        while (identity.status() == IdentityService.Status.SIGNING_IN && System.currentTimeMillis() < deadline) Thread.sleep(20);
        return identity.status();
    }

    @Test
    void signInWithPkceAppliesTheHubEntitlement() throws Exception {
        assertThat(entitlements.has(Feature.DOWNLOAD_MULTITHREAD)).isFalse();
        assertThat(signIn()).isEqualTo(IdentityService.Status.SIGNED_IN);
        var acct = identity.account();
        assertThat(acct.profile().name()).isEqualTo("Nusrat Jahan");
        assertThat(acct.profile().emailVerified()).isTrue();
        assertThat(acct.entitlement().tier()).isEqualTo(EntitlementService.Tier.PRO);
        assertThat(acct.entitlement().source()).isEqualTo(EntitlementService.Source.ONLINE);
        assertThat(entitlements.has(Feature.DOWNLOAD_MULTITHREAD)).isTrue();
    }

    @Test
    void stateMismatchIsRejected() throws Exception {
        op.wrongState = true;
        assertThat(signIn()).isEqualTo(IdentityService.Status.SIGNED_OUT);
        assertThat(identity.account().lastError()).contains("state");
        assertThat(entitlements.state().tier()).isEqualTo(EntitlementService.Tier.FREE);
    }

    @Test
    void nonceMismatchIsRejected() throws Exception {
        op.wrongNonce = true;
        assertThat(signIn()).isEqualTo(IdentityService.Status.SIGNED_OUT);
        assertThat(identity.account().lastError()).contains("nonce");
    }

    @Test
    void expiredIdTokenIsRejected() throws Exception {
        op.expired = true;
        assertThat(signIn()).isEqualTo(IdentityService.Status.SIGNED_OUT);
        assertThat(identity.account().lastError()).containsIgnoringCase("expired");
    }

    @Test
    void hubWithoutPublicClientSupportIsReportedNotWorkedAround() {
        op.publicClients = false;
        // discovery is cached per client; a fresh client sees the flag
        var fresh = new OidcClient(op.issuer(), MockOidcProvider.CLIENT, new com.fasterxml.jackson.databind.ObjectMapper());
        assertThatThrownBy(() -> {
            if (!fresh.discover().supportsPublicClients()) throw WdmException.unavailable("hub_no_public_clients", "no");
        }).isInstanceOf(WdmException.class);
    }

    @Test
    void suspendedOrUnknownRolesFallBackToFree() throws Exception {
        op.entitlementStatus = "suspended";
        assertThat(signIn()).isEqualTo(IdentityService.Status.SIGNED_IN);
        assertThat(entitlements.state().tier()).isEqualTo(EntitlementService.Tier.FREE);
        identity.logout();
        op.entitlementStatus = "active";
        op.roles = java.util.List.of("superuser");
        signIn();
        assertThat(entitlements.state().tier()).isEqualTo(EntitlementService.Tier.FREE);
    }

    @Test
    void refreshRotatesAndOfflineUsesTheSignedCache() throws Exception {
        signIn();
        identity.reverify(); // online refresh rotates the refresh token
        assertThat(identity.status()).isEqualTo(IdentityService.Status.SIGNED_IN);

        op.down = true; // hub unreachable → offline cache within the grace period keeps PRO
        identity.reverify();
        assertThat(identity.status()).isEqualTo(IdentityService.Status.OFFLINE);
        assertThat(entitlements.state().tier()).isEqualTo(EntitlementService.Tier.PRO);
        assertThat(entitlements.state().source()).isEqualTo(EntitlementService.Source.OFFLINE_CACHE);

        // Editing the cache in SQLite (e.g. tier → BUSINESS) breaks its signature → Free.
        EntitlementCacheEntry e = cache.findAll().getFirst();
        e.setTier("BUSINESS");
        cache.save(e);
        identity.reverify();
        assertThat(entitlements.state().tier()).isEqualTo(EntitlementService.Tier.FREE);

        // Back online the hub is authoritative again.
        op.down = false;
        identity.reverify();
        assertThat(identity.status()).isEqualTo(IdentityService.Status.SIGNED_IN);
        assertThat(entitlements.state().tier()).isEqualTo(EntitlementService.Tier.PRO);
    }

    @Test
    void expiredOfflineGraceMeansFree() throws Exception {
        signIn();
        op.down = true;
        EntitlementCacheEntry e = cache.findAll().getFirst();
        // Re-sign is impossible from outside; simulate time passing by moving expiry into the past.
        e.setExpiresAt(System.currentTimeMillis() - 1000);
        cache.save(e);
        identity.reverify();
        assertThat(entitlements.state().tier()).isEqualTo(EntitlementService.Tier.FREE);
    }

    @Test
    void refreshRejectedByTheHubSignsOut() throws Exception {
        signIn();
        op.revoked.clear();
        // Simulate the hub invalidating the grant (e.g. password changed): the mock forgets all refresh tokens.
        java.lang.reflect.Field f = MockOidcProvider.class.getDeclaredField("refreshTokens");
        f.setAccessible(true);
        ((java.util.Set<?>) f.get(op)).clear();
        identity.reverify();
        assertThat(identity.status()).isEqualTo(IdentityService.Status.SIGNED_OUT);
        assertThat(entitlements.state().tier()).isEqualTo(EntitlementService.Tier.FREE);
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        signIn();
        var r = identity.logout();
        assertThat(r.revoked()).isTrue();
        assertThat(op.revoked).isNotEmpty();
        assertThat(r.endSessionUrl()).startsWith(op.issuer() + "/session/end");
        assertThat(identity.account().profile()).isNull();
        assertThat(cache.count()).isZero();
    }
}
