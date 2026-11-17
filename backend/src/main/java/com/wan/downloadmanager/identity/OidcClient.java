package com.wan.downloadmanager.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OpenID Connect relying party for a public native client (Authorization Code + PKCE S256, no
 * client secret). Everything is learned from the issuer's discovery document; no endpoint is assumed.
 */
public class OidcClient {
    public record Discovery(String issuer, URI authorization, URI token, URI userinfo, URI jwks, URI revocation,
                            URI endSession, Set<String> scopes, Set<String> authMethods) {
        public boolean supportsPublicClients() { return authMethods.contains("none"); }
    }

    public record Pkce(String verifier, String challenge) {}

    public record Tokens(String accessToken, String refreshToken, String idToken, long expiresIn) {}

    /** The authorization server refused (invalid_grant, …): the stored session is no longer valid. */
    public static class OidcRejected extends IOException {
        private final String error;
        public OidcRejected(String error, String description) {
            super(error + (description == null ? "" : ": " + description));
            this.error = error;
        }
        public String error() { return error; }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private final String issuer;
    private final String clientId;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private volatile Discovery discovery;
    private volatile DefaultJWTProcessor<SecurityContext> processor;

    public OidcClient(String issuer, String clientId, ObjectMapper mapper) {
        this.issuer = issuer.replaceAll("/+$", "");
        this.clientId = clientId;
        this.mapper = mapper;
        URI u = URI.create(this.issuer);
        boolean localHttp = "http".equals(u.getScheme()) && Set.of("localhost", "127.0.0.1", "[::1]").contains(u.getHost());
        if (!"https".equals(u.getScheme()) && !localHttp) {
            throw new IllegalArgumentException("The WAN Identity issuer must use https (plain http only for localhost development)");
        }
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public String clientId() { return clientId; }

    public Discovery discover() throws IOException, InterruptedException {
        Discovery d = discovery;
        if (d != null) return d;
        JsonNode doc = getJson(URI.create(issuer + "/.well-known/openid-configuration"), null);
        String iss = text(doc, "issuer");
        if (!issuer.equals(iss)) throw new IOException("Discovery issuer mismatch: expected " + issuer + ", got " + iss);
        d = new Discovery(iss, uri(doc, "authorization_endpoint", true), uri(doc, "token_endpoint", true),
                uri(doc, "userinfo_endpoint", false), uri(doc, "jwks_uri", true), uri(doc, "revocation_endpoint", false),
                uri(doc, "end_session_endpoint", false), set(doc, "scopes_supported"), set(doc, "token_endpoint_auth_methods_supported"));
        discovery = d;
        return d;
    }

    public static Pkce newPkce() {
        String verifier = randomToken(48);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return new Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(hash));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Scopes we ask for, limited to what the issuer advertises (so an older hub still works). */
    public String effectiveScopes(String wanted) throws IOException, InterruptedException {
        Set<String> supported = discover().scopes();
        return Arrays.stream(wanted.trim().split("\\s+"))
                .filter(s -> s.equals("openid") || supported.isEmpty() || supported.contains(s))
                .collect(Collectors.joining(" "));
    }

    public URI authorizationUrl(String redirectUri, String scopes, String state, String nonce, Pkce pkce, boolean signup)
            throws IOException, InterruptedException {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_id", clientId);
        q.put("response_type", "code");
        q.put("redirect_uri", redirectUri);
        q.put("scope", scopes);
        q.put("state", state);
        q.put("nonce", nonce);
        q.put("code_challenge", pkce.challenge());
        q.put("code_challenge_method", "S256");
        // oidc-provider honours offline_access (refresh tokens) only together with prompt=consent.
        if (scopes.contains("offline_access")) q.put("prompt", "consent");
        if (signup) q.put("screen_hint", "signup");
        return URI.create(discover().authorization() + "?" + form(q));
    }

    public Tokens exchangeCode(String code, String redirectUri, String verifier) throws IOException, InterruptedException {
        return tokenRequest(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", redirectUri,
                "client_id", clientId, "code_verifier", verifier));
    }

    public Tokens refresh(String refreshToken) throws IOException, InterruptedException {
        return tokenRequest(Map.of("grant_type", "refresh_token", "refresh_token", refreshToken, "client_id", clientId));
    }

    /** RFC 7009 revocation, if the issuer offers it. Returns false when unsupported. */
    public boolean revoke(String token, String hint) throws IOException, InterruptedException {
        URI endpoint = discover().revocation();
        if (endpoint == null) return false;
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("token", token, "token_type_hint", hint, "client_id", clientId))))
                .build(), HttpResponse.BodyHandlers.ofString());
        return res.statusCode() == 200;
    }

    public Map<String, Object> userinfo(String accessToken) throws IOException, InterruptedException {
        URI endpoint = discover().userinfo();
        if (endpoint == null) return Map.of();
        JsonNode n = getJson(endpoint, accessToken);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = mapper.convertValue(n, Map.class);
        return m;
    }

    /**
     * Validates an ID token: RS256 signature against the issuer's JWKS, iss, aud contains our
     * client id, azp (if present) is our client id, exp/iat (60 s skew) and the nonce we sent
     * (when {@code expectedNonce} is not null — refresh responses carry no fresh nonce).
     */
    public JWTClaimsSet validateIdToken(String idToken, String expectedNonce) throws IOException, InterruptedException {
        try {
            JWTClaimsSet claims = processor().process(idToken, null);
            List<String> aud = claims.getAudience();
            if (aud == null || !aud.contains(clientId)) throw new IOException("ID token audience mismatch");
            String azp = claims.getStringClaim("azp");
            if (azp != null && !azp.equals(clientId)) throw new IOException("ID token azp mismatch");
            if (aud.size() > 1 && azp == null) throw new IOException("ID token with several audiences lacks azp");
            if (expectedNonce != null && !expectedNonce.equals(claims.getStringClaim("nonce"))) throw new IOException("ID token nonce mismatch");
            return claims;
        } catch (ParseException | BadJOSEException | JOSEException e) {
            throw new IOException("Invalid ID token: " + e.getMessage(), e);
        }
    }

    private DefaultJWTProcessor<SecurityContext> processor() throws IOException, InterruptedException {
        DefaultJWTProcessor<SecurityContext> p = processor;
        if (p != null) return p;
        Discovery d = discover();
        URL jwks = d.jwks().toURL();
        JWKSource<SecurityContext> source = JWKSourceBuilder.create(jwks).retrying(true).build();
        p = new DefaultJWTProcessor<>();
        p.setJWSKeySelector(new JWSVerificationKeySelector<>(Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256, JWSAlgorithm.PS256), source));
        DefaultJWTClaimsVerifier<SecurityContext> verifier = new DefaultJWTClaimsVerifier<>(
                new JWTClaimsSet.Builder().issuer(d.issuer()).build(), Set.of("sub", "iat", "exp", "aud"));
        verifier.setMaxClockSkew(60);
        p.setJWTClaimsSetVerifier(verifier);
        processor = p;
        return p;
    }

    private Tokens tokenRequest(Map<String, String> params) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(discover().token()).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(params))).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode body = res.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(res.body());
        if (res.statusCode() >= 400 && res.statusCode() < 500 && body.hasNonNull("error")) {
            throw new OidcRejected(body.get("error").asText(), text(body, "error_description"));
        }
        if (res.statusCode() != 200) throw new IOException("Token endpoint answered HTTP " + res.statusCode());
        return new Tokens(text(body, "access_token"), text(body, "refresh_token"), text(body, "id_token"),
                body.path("expires_in").asLong(0));
    }

    private JsonNode getJson(URI uri, String bearer) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET();
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 401) throw new OidcRejected("invalid_token", "access token rejected");
        if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode() + " from " + uri.getPath());
        return mapper.readTree(res.body());
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static String text(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asText() : null;
    }

    private static URI uri(JsonNode n, String f, boolean required) throws IOException {
        String v = text(n, f);
        if (v == null) {
            if (required) throw new IOException("Discovery document lacks " + f);
            return null;
        }
        return URI.create(v);
    }

    private static Set<String> set(JsonNode n, String f) {
        Set<String> out = new HashSet<>();
        n.path(f).forEach(x -> out.add(x.asText()));
        return out;
    }
}
