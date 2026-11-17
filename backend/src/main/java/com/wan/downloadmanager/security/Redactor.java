package com.wan.downloadmanager.security;

import java.util.regex.Pattern;

/** Removes secrets from text before it is logged or stored as an event. */
public final class Redactor {
    private Redactor() {}

    private static final Pattern SENSITIVE_QUERY = Pattern.compile(
            "(?i)([?&](?:access_token|refresh_token|id_token|token|code|code_verifier|key|api_key|apikey|sig|signature|password|secret|auth|session|X-Amz-Signature|X-Amz-Credential|Policy|Key-Pair-Id)=)[^&#\\s]*");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");

    public static String redact(String text) {
        if (text == null) return null;
        String s = SENSITIVE_QUERY.matcher(text).replaceAll("$1[redacted]");
        s = BEARER.matcher(s).replaceAll("$1[redacted]");
        return JWT.matcher(s).replaceAll("[redacted-jwt]");
    }
}
