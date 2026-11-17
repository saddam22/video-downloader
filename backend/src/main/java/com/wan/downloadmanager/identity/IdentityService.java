package com.wan.downloadmanager.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import com.wan.downloadmanager.config.WanProperties;
import com.wan.downloadmanager.entitlement.EntitlementService;
import com.wan.downloadmanager.entitlement.EntitlementService.Source;
import com.wan.downloadmanager.entitlement.EntitlementService.State;
import com.wan.downloadmanager.entitlement.EntitlementService.Tier;
import com.wan.downloadmanager.entity.AccountCacheEntry;
import com.wan.downloadmanager.entity.DeviceRegistration;
import com.wan.downloadmanager.entity.EntitlementCacheEntry;
import com.wan.downloadmanager.exception.WdmException;
import com.wan.downloadmanager.notification.EventBus;
import com.wan.downloadmanager.notification.EventLog;
import com.wan.downloadmanager.repository.AccountCacheEntryRepository;
import com.wan.downloadmanager.repository.DeviceRegistrationRepository;
import com.wan.downloadmanager.repository.EntitlementCacheEntryRepository;
import com.wan.downloadmanager.security.Redactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WAN Identity Hub session of this installation. There is no local password and no local account:
 * identity, registration, MFA and recovery all happen on the hub, in the system browser.
 */
@Service
public class IdentityService {
    private static final Logger log = LoggerFactory.getLogger(IdentityService.class);
    static final String VAULT_REFRESH = "wan-refresh-token";
    static final String VAULT_ID_TOKEN = "wan-id-token";
    static final String VAULT_CACHE_KEY = "offline-cache-key";

    public enum Status { NOT_CONFIGURED, SIGNED_OUT, SIGNING_IN, SIGNED_IN, OFFLINE }

    public record Profile(String sub, String name, String username, String email, Boolean emailVerified,
                          String phone, Boolean phoneVerified, String locale) {}

    public record Device(String installationId, String name, String registeredWith, String note) {}

    public record AccountView(Status status, boolean configured, String issuer, Profile profile, State entitlement,
                              List<String> features, String vault, boolean vaultPersistent, Device device,
                              String hubWebUrl, String billingUrl, String lastError, String loginUrl) {}

    private final WanProperties props;
    private final ObjectMapper mapper;
    private final CredentialVault vault;
    private final BrowserLauncher browser;
    private final EntitlementService entitlements;
    private final AccountCacheEntryRepository accounts;
    private final EntitlementCacheEntryRepository cache;
    private final DeviceRegistrationRepository devices;
    private final EventLog events;
    private final EventBus bus;
    private final OidcClient oidc;
    private final AtomicBoolean loginRunning = new AtomicBoolean();
    private volatile Status status;
    private volatile Profile profile;
    private volatile String lastError;
    private volatile String pendingLoginUrl;
    private volatile LoopbackReceiver activeReceiver;

    public IdentityService(WanProperties props, ObjectMapper mapper, CredentialVault vault, BrowserLauncher browser,
                           EntitlementService entitlements, AccountCacheEntryRepository accounts,
                           EntitlementCacheEntryRepository cache, DeviceRegistrationRepository devices, EventLog events, EventBus bus) {
        this.props = props;
        this.mapper = mapper;
        this.vault = vault;
        this.browser = browser;
        this.entitlements = entitlements;
        this.accounts = accounts;
        this.cache = cache;
        this.devices = devices;
        this.events = events;
        this.bus = bus;
        this.oidc = props.identity().configured() ? new OidcClient(props.identity().issuer(), props.identity().clientId(), mapper) : null;
        this.status = oidc == null ? Status.NOT_CONFIGURED : Status.SIGNED_OUT;
        if (oidc == null) entitlements.apply(State.free(Source.NOT_CONFIGURED));
    }

    // ------------------------------------------------------------------ startup / restore

    @EventListener(ApplicationReadyEvent.class)
    public void restore() {
        ensureDevice();
        if (oidc == null) return;
        if (vault.get(VAULT_REFRESH).isEmpty()) {
            setStatus(Status.SIGNED_OUT, null);
            return;
        }
        Thread.ofVirtual().name("wdm-identity-restore").start(this::reverify);
    }

    /** Refreshes the session; on network failure falls back to the signed offline cache. */
    @Scheduled(fixedDelay = 30 * 60_000, initialDelay = 30 * 60_000)
    public void reverify() {
        if (oidc == null || status == Status.SIGNING_IN) return;
        Optional<String> rt = vault.get(VAULT_REFRESH);
        if (rt.isEmpty()) return;
        try {
            OidcClient.Tokens t = oidc.refresh(rt.get());
            if (t.refreshToken() != null) vault.put(VAULT_REFRESH, t.refreshToken());
            JWTClaimsSet claims = t.idToken() != null ? oidc.validateIdToken(t.idToken(), null) : null;
            if (t.idToken() != null) vault.put(VAULT_ID_TOKEN, t.idToken());
            String expectedSub = cache.findAll().stream().map(EntitlementCacheEntry::getSub).findFirst().orElse(null);
            if (claims != null && expectedSub != null && !expectedSub.equals(claims.getSubject())) {
                throw new IOException("Refreshed identity does not match the signed-in account");
            }
            establish(claims, t.accessToken());
        } catch (OidcClient.OidcRejected e) {
            log.info("WAN session no longer valid: {}", e.error());
            events.audit("identity", "Session ended by WAN Identity Hub (" + e.error() + ")", null);
            clearLocal();
            setStatus(Status.SIGNED_OUT, "Your WAN session has ended. Please sign in again.");
        } catch (IOException e) {
            useOfflineCache(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ login

    public record LoginStart(String authorizationUrl, String redirectUri) {}

    /**
     * Starts Authorization Code + PKCE in the system browser. Returns at once; completion is
     * published on the {@code notifications} topic and reflected in {@link #account()}.
     */
    public LoginStart startLogin(boolean signup) {
        if (oidc == null) throw WdmException.unavailable("identity_not_configured", "WAN Identity Hub is not configured for this installation");
        if (!loginRunning.compareAndSet(false, true)) throw WdmException.conflict("login_in_progress", "A sign-in is already in progress");
        try {
            OidcClient.Discovery d = oidc.discover();
            if (!d.supportsPublicClients()) {
                throw WdmException.unavailable("hub_no_public_clients",
                        "This WAN Identity Hub does not accept desktop (public) clients yet — see docs/WAN-IDENTITY-INTEGRATION.md");
            }
            LoopbackReceiver receiver = new LoopbackReceiver(props.identity().redirectPath());
            activeReceiver = receiver;
            OidcClient.Pkce pkce = OidcClient.newPkce();
            String state = OidcClient.randomToken(24);
            String nonce = OidcClient.randomToken(24);
            URI url = oidc.authorizationUrl(receiver.redirectUri(), oidc.effectiveScopes(props.identity().scopes()), state, nonce, pkce, signup);
            pendingLoginUrl = url.toString();
            setStatus(Status.SIGNING_IN, null);
            Thread.ofVirtual().name("wdm-login").start(() -> completeLogin(receiver, state, nonce, pkce));
            try {
                browser.open(url);
            } catch (Exception e) {
                log.warn("Could not open the system browser: {}", e.toString());
            }
            return new LoginStart(url.toString(), receiver.redirectUri());
        } catch (IOException e) {
            loginRunning.set(false);
            setStatus(previousSignedOutStatus(), "Cannot reach WAN Identity Hub: " + e.getMessage());
            throw WdmException.unavailable("hub_unreachable", "Cannot reach WAN Identity Hub: " + e.getMessage());
        } catch (InterruptedException e) {
            loginRunning.set(false);
            Thread.currentThread().interrupt();
            throw WdmException.unavailable("interrupted", "Interrupted");
        } catch (RuntimeException e) {
            loginRunning.set(false);
            if (status == Status.SIGNING_IN) setStatus(previousSignedOutStatus(), e.getMessage());
            throw e;
        }
    }

    public void cancelLogin() {
        LoopbackReceiver r = activeReceiver;
        if (r != null) r.close();
    }

    private void completeLogin(LoopbackReceiver receiver, String state, String nonce, OidcClient.Pkce pkce) {
        try (receiver) {
            Map<String, String> cb = receiver.result().get(props.identity().loginTimeoutSeconds(), TimeUnit.SECONDS);
            if (!MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), cb.getOrDefault("state", "").getBytes(StandardCharsets.UTF_8))) {
                throw new IOException("The sign-in response did not match this request (state)");
            }
            if (cb.containsKey("error")) {
                throw new IOException(cb.get("error").equals("access_denied")
                        ? "Access was denied by WAN Identity Hub" + (cb.containsKey("error_description") ? ": " + cb.get("error_description") : "")
                        : "Sign-in failed: " + cb.get("error"));
            }
            String iss = cb.get("iss");
            if (iss != null && !iss.equals(oidc.discover().issuer())) throw new IOException("The sign-in response came from another issuer");
            String code = cb.get("code");
            if (code == null || code.isBlank()) throw new IOException("The sign-in response had no authorization code");
            OidcClient.Tokens t = oidc.exchangeCode(code, receiver.redirectUri(), pkce.verifier());
            if (t.idToken() == null) throw new IOException("WAN Identity Hub returned no ID token");
            JWTClaimsSet claims = oidc.validateIdToken(t.idToken(), nonce);
            if (t.refreshToken() != null) vault.put(VAULT_REFRESH, t.refreshToken());
            vault.put(VAULT_ID_TOKEN, t.idToken());
            establish(claims, t.accessToken());
            events.audit("identity", "Signed in as " + profile.sub(), profile.sub());
            bus.notify("success", "Signed in", "Welcome, " + Objects.requireNonNullElse(profile.name(), "WAN user"), null);
        } catch (TimeoutException e) {
            setStatus(previousSignedOutStatus(), "Sign-in timed out. Please try again.");
        } catch (java.util.concurrent.CancellationException e) {
            setStatus(previousSignedOutStatus(), "Sign-in was cancelled.");
        } catch (Exception e) {
            String msg = Redactor.redact(e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            log.warn("Sign-in failed: {}", msg);
            events.warn("identity", "Sign-in failed: " + msg, null);
            setStatus(previousSignedOutStatus(), msg);
            bus.notify("error", "Sign-in failed", msg, null);
        } finally {
            pendingLoginUrl = null;
            activeReceiver = null;
            loginRunning.set(false);
        }
    }

    private Status previousSignedOutStatus() {
        return profile != null && vault.get(VAULT_REFRESH).isPresent() ? Status.SIGNED_IN : Status.SIGNED_OUT;
    }

    /** Applies verified claims: profile, entitlement, offline cache. */
    private void establish(JWTClaimsSet idClaims, String accessToken) throws IOException, InterruptedException {
        Map<String, Object> claims = new HashMap<>();
        if (idClaims != null) claims.putAll(idClaims.getClaims());
        if (accessToken != null) {
            Map<String, Object> ui = oidc.userinfo(accessToken);
            if (idClaims != null && ui.containsKey("sub") && !idClaims.getSubject().equals(ui.get("sub"))) {
                throw new IOException("userinfo subject does not match the ID token");
            }
            claims.putAll(ui);
        }
        String sub = (String) claims.get("sub");
        if (sub == null) throw new IOException("No subject in WAN identity");
        Profile p = new Profile(sub, str(claims.get("name")), str(claims.get("preferred_username")), str(claims.get("email")),
                bool(claims.get("email_verified")), str(claims.get("phone_number")), bool(claims.get("phone_number_verified")),
                str(claims.get("locale")));
        this.profile = p;
        saveAccount(p);

        String hubStatus = "none";
        List<String> roles = List.of();
        Object ent = claims.get("wan_entitlement");
        if (ent instanceof Map<?, ?> m && props.licensing().platformId().equals(m.get("platform"))) {
            hubStatus = String.valueOf(m.get("status"));
            if (m.get("roles") instanceof List<?> l) roles = l.stream().map(String::valueOf).toList();
        }
        Tier tier = EntitlementService.tierFromRoles(hubStatus, roles);
        long now = System.currentTimeMillis();
        long until = now + Duration.ofDays(Math.max(0, props.licensing().offlineGraceDays())).toMillis();
        writeCache(sub, tier, hubStatus, roles, now, until);
        entitlements.apply(new State(tier, Source.ONLINE, hubStatus, roles, now, until));
        markDeviceVerified(now);
        lastError = null;
        setStatus(Status.SIGNED_IN, null);
    }

    private void useOfflineCache(Exception cause) {
        log.info("WAN Identity Hub unreachable ({}); checking the offline cache", cause.toString());
        Optional<EntitlementCacheEntry> row = cache.findAll().stream().findFirst();
        long now = System.currentTimeMillis();
        if (row.isPresent() && verifyCache(row.get()) && row.get().getExpiresAt() > now) {
            EntitlementCacheEntry e = row.get();
            this.profile = accounts.findById(e.getSub()).map(IdentityService::toProfile).orElse(null);
            entitlements.apply(new State(Tier.valueOf(e.getTier()), Source.OFFLINE_CACHE, e.getStatus(), splitRoles(e.getRoles()),
                    e.getVerifiedAt(), e.getExpiresAt()));
            setStatus(Status.OFFLINE, "Working offline until WAN Identity Hub is reachable again.");
        } else {
            if (row.isPresent() && !verifyCache(row.get())) events.warn("identity", "Offline entitlement cache failed its integrity check; ignored", null);
            this.profile = row.flatMap(r -> accounts.findById(r.getSub())).map(IdentityService::toProfile).orElse(null);
            entitlements.apply(State.free(Source.OFFLINE_CACHE));
            setStatus(Status.OFFLINE, "Offline and the last verification is too old (or missing): Free features only.");
        }
    }

    // ------------------------------------------------------------------ logout

    public record LogoutResult(boolean revoked, String endSessionUrl) {}

    public LogoutResult logout() {
        boolean revoked = false;
        String endSession = null;
        Optional<String> rt = vault.get(VAULT_REFRESH);
        Optional<String> idToken = vault.get(VAULT_ID_TOKEN);
        if (oidc != null) {
            try {
                if (rt.isPresent()) revoked = oidc.revoke(rt.get(), "refresh_token");
                URI es = oidc.discover().endSession();
                if (es != null && idToken.isPresent()) {
                    endSession = es + "?client_id=" + oidc.clientId() + "&id_token_hint=" + idToken.get();
                }
            } catch (IOException e) {
                log.info("Could not revoke at WAN Identity Hub ({}); local sign-out only", e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String sub = profile != null ? profile.sub() : null;
        clearLocal();
        events.audit("identity", "Signed out" + (revoked ? " (refresh token revoked)" : " (local only)"), sub);
        setStatus(oidc == null ? Status.NOT_CONFIGURED : Status.SIGNED_OUT, null);
        return new LogoutResult(revoked, endSession);
    }

    private void clearLocal() {
        vault.delete(VAULT_REFRESH);
        vault.delete(VAULT_ID_TOKEN);
        cache.deleteAll();
        accounts.deleteAll();
        profile = null;
        entitlements.apply(State.free(oidc == null ? Source.NOT_CONFIGURED : Source.SIGNED_OUT));
    }

    // ------------------------------------------------------------------ views

    public AccountView account() {
        State st = entitlements.state();
        List<String> features = entitlements.plan().features().stream().map(f -> f.key()).sorted().toList();
        return new AccountView(status, oidc != null, props.identity().issuer(), profile, st, features, vault.description(),
                vault.persistent(), device(), blankToNull(props.identity().hubWebUrl()), blankToNull(props.billing().url()),
                lastError, pendingLoginUrl);
    }

    public Status status() {
        return status;
    }

    private void setStatus(Status s, String error) {
        this.status = s;
        this.lastError = error;
        bus.publish(EventBus.NOTIFICATIONS, Map.of("type", "account", "status", s.name()));
    }

    // ------------------------------------------------------------------ offline cache integrity

    private void writeCache(String sub, Tier tier, String hubStatus, List<String> roles, long verifiedAt, long expiresAt) {
        cache.deleteAll();
        EntitlementCacheEntry e = new EntitlementCacheEntry();
        e.setSub(sub);
        e.setTier(tier.name());
        e.setStatus(hubStatus);
        e.setRoles(String.join(",", roles));
        e.setVerifiedAt(verifiedAt);
        e.setExpiresAt(expiresAt);
        e.setSignature(sign(e));
        cache.save(e);
    }

    boolean verifyCache(EntitlementCacheEntry e) {
        String expected = sign(e);
        return expected != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                Objects.requireNonNullElse(e.getSignature(), "").getBytes(StandardCharsets.UTF_8));
    }

    private String sign(EntitlementCacheEntry e) {
        String key = vault.get(VAULT_CACHE_KEY).orElseGet(() -> {
            String k = OidcClient.randomToken(32);
            vault.put(VAULT_CACHE_KEY, k);
            return k;
        });
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String payload = String.join("|", e.getSub(), e.getTier(), e.getStatus(), e.getRoles(),
                    Long.toString(e.getVerifiedAt()), Long.toString(e.getExpiresAt()));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ device

    private void ensureDevice() {
        if (devices.count() > 0) return;
        DeviceRegistration d = new DeviceRegistration();
        d.setInstallationId(UUID.randomUUID().toString());
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "This computer";
        }
        d.setDeviceName(host + " · " + System.getProperty("os.name", ""));
        d.setCreatedAt(System.currentTimeMillis());
        devices.save(d);
    }

    private void markDeviceVerified(long at) {
        devices.findAll().stream().findFirst().ifPresent(d -> {
            d.setLastVerifiedAt(at);
            devices.save(d);
        });
    }

    private Device device() {
        return devices.findAll().stream().findFirst()
                .map(d -> new Device(d.getInstallationId(), d.getDeviceName(), d.getRegisteredWith(),
                        "WAN Identity Hub has no device registration API yet; this installation is tracked locally only."))
                .orElse(null);
    }

    // ------------------------------------------------------------------ helpers

    private void saveAccount(Profile p) {
        accounts.deleteAll();
        AccountCacheEntry a = new AccountCacheEntry();
        a.setSub(p.sub());
        a.setName(p.name());
        a.setPreferredUsername(p.username());
        a.setEmail(p.email());
        a.setEmailVerified(p.emailVerified());
        a.setPhone(p.phone());
        a.setPhoneVerified(p.phoneVerified());
        a.setLocale(p.locale());
        a.setIssuer(props.identity().issuer());
        a.setVerifiedAt(System.currentTimeMillis());
        accounts.save(a);
    }

    private static Profile toProfile(AccountCacheEntry a) {
        return new Profile(a.getSub(), a.getName(), a.getPreferredUsername(), a.getEmail(), a.getEmailVerified(), a.getPhone(),
                a.getPhoneVerified(), a.getLocale());
    }

    private static List<String> splitRoles(String s) {
        return s == null || s.isBlank() ? List.of() : List.of(s.split(","));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Boolean bool(Object o) {
        return o instanceof Boolean b ? b : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    ObjectMapper mapper() {
        return mapper;
    }
}
