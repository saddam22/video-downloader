package com.wan.downloadmanager.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal OpenID Provider with the same contract as the patched WAN Identity Hub: discovery, JWKS,
 * code + PKCE S256 (public client), rotating refresh tokens, userinfo, revocation, wan_entitlement claim.
 * Knobs let tests break specific checks.
 */
public class MockOidcProvider implements AutoCloseable {
    public static final String CLIENT = "wan-download-manager";
    private final HttpServer server;
    private final RSAKey key;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Map<String, String>> codes = new ConcurrentHashMap<>();
    private final Set<String> refreshTokens = ConcurrentHashMap.newKeySet();
    public final Set<String> revoked = ConcurrentHashMap.newKeySet();

    public volatile String sub = "8b0c2b8e-6d3e-4d8e-9a55-0f2d3c4b5a69";
    public volatile List<String> roles = List.of("pro");
    public volatile String entitlementStatus = "active";
    public volatile boolean wrongNonce;
    public volatile boolean expired;
    public volatile boolean wrongState;
    public volatile boolean down;
    public volatile boolean publicClients = true;

    public MockOidcProvider() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        server.createContext("/oidc", this::handle);
        server.start();
    }

    public String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/oidc";
    }

    /** Plays the user's browser: authorize → (signed in already) → redirect to the app's loopback. */
    public void browse(URI authorize) throws Exception {
        Map<String, String> q = query(authorize.getRawQuery());
        String code = OidcClient.randomToken(16);
        codes.put(code, q);
        String state = wrongState ? "tampered" : q.get("state");
        URI callback = URI.create(q.get("redirect_uri") + "?code=" + code + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8)
                + "&iss=" + URLEncoder.encode(issuer(), StandardCharsets.UTF_8));
        var http = java.net.http.HttpClient.newHttpClient();
        http.send(java.net.http.HttpRequest.newBuilder(callback).build(), java.net.http.HttpResponse.BodyHandlers.discarding());
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            if (down) {
                ex.sendResponseHeaders(503, -1);
                return;
            }
            String path = ex.getRequestURI().getPath().substring("/oidc".length());
            switch (path) {
                case "/.well-known/openid-configuration" -> json(ex, 200, Map.of(
                        "issuer", issuer(), "authorization_endpoint", issuer() + "/auth", "token_endpoint", issuer() + "/token",
                        "userinfo_endpoint", issuer() + "/me", "jwks_uri", issuer() + "/jwks",
                        "revocation_endpoint", issuer() + "/token/revocation", "end_session_endpoint", issuer() + "/session/end",
                        "scopes_supported", List.of("openid", "profile", "email", "offline_access", "wan_entitlements"),
                        "token_endpoint_auth_methods_supported", publicClients ? List.of("none", "client_secret_basic") : List.of("client_secret_basic")));
                case "/jwks" -> json(ex, 200, new JWKSet(key.toPublicJWK()).toJSONObject());
                case "/token" -> token(ex);
                case "/me" -> {
                    String auth = ex.getRequestHeaders().getFirst("Authorization");
                    if (auth == null || !auth.startsWith("Bearer at-")) {
                        json(ex, 401, Map.of("error", "invalid_token"));
                        return;
                    }
                    json(ex, 200, claims());
                }
                case "/token/revocation" -> {
                    Map<String, String> f = query(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    revoked.add(f.get("token"));
                    refreshTokens.remove(f.get("token"));
                    ex.sendResponseHeaders(200, -1);
                }
                default -> ex.sendResponseHeaders(404, -1);
            }
        } catch (Exception e) {
            ex.sendResponseHeaders(500, -1);
        }
    }

    private void token(HttpExchange ex) throws Exception {
        Map<String, String> f = query(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (!CLIENT.equals(f.get("client_id"))) {
            json(ex, 401, Map.of("error", "invalid_client"));
            return;
        }
        String nonce = null;
        if ("authorization_code".equals(f.get("grant_type"))) {
            Map<String, String> req = codes.remove(f.get("code"));
            if (req == null || !req.get("redirect_uri").equals(f.get("redirect_uri"))) {
                json(ex, 400, Map.of("error", "invalid_grant"));
                return;
            }
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(f.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
            if (!challenge.equals(req.get("code_challenge"))) {
                json(ex, 400, Map.of("error", "invalid_grant", "error_description", "PKCE verification failed"));
                return;
            }
            nonce = wrongNonce ? "other" : req.get("nonce");
        } else if ("refresh_token".equals(f.get("grant_type"))) {
            if (!refreshTokens.remove(f.get("refresh_token"))) {
                json(ex, 400, Map.of("error", "invalid_grant", "error_description", "grant request is invalid"));
                return;
            }
        } else {
            json(ex, 400, Map.of("error", "unsupported_grant_type"));
            return;
        }
        String rt = "rt-" + OidcClient.randomToken(16);
        refreshTokens.add(rt);
        json(ex, 200, Map.of("access_token", "at-" + OidcClient.randomToken(16), "refresh_token", rt,
                "id_token", idToken(nonce), "token_type", "Bearer", "expires_in", 600));
    }

    private String idToken(String nonce) throws Exception {
        long now = System.currentTimeMillis();
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().issuer(issuer()).subject(sub).audience(CLIENT)
                .issueTime(new Date(expired ? now - 7200_000 : now))
                .expirationTime(new Date(expired ? now - 3600_000 : now + 600_000));
        if (nonce != null) b.claim("nonce", nonce);
        claims().forEach(b::claim);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").type(JOSEObjectType.JWT).build(), b.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private Map<String, Object> claims() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("sub", sub);
        c.put("name", "Nusrat Jahan");
        c.put("preferred_username", "nusrat");
        c.put("email", "nusrat@example.com");
        c.put("email_verified", true);
        c.put("wan_entitlement", Map.of("platform", CLIENT, "status", entitlementStatus, "roles", roles));
        return c;
    }

    private void json(HttpExchange ex, int status, Object body) throws IOException {
        byte[] b = mapper.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String p : raw.split("&")) {
            int i = p.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(p.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
