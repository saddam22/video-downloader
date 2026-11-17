package com.wan.downloadmanager.security;

import com.wan.downloadmanager.config.WanProperties;
import com.wan.downloadmanager.exception.WdmException;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * SSRF / URL validation for anything the app fetches (including every redirect hop).
 * Allowed: http and https with a host, no user-info. Refused: loopback, link-local (incl. cloud
 * metadata 169.254.169.254), wildcard, multicast and unique-local IPv6 unless explicitly enabled;
 * private LAN ranges are allowed by default (NAS / intranet downloads) and can be disabled.
 * Note: a host that re-resolves between this check and the connection (DNS rebinding) is mitigated
 * by checking again on every redirect and by the local API's own Host/Origin checks.
 */
@Component
public class UrlPolicy {
    public static final int MAX_URL_LENGTH = 8192;

    private final boolean allowLoopback;
    private final boolean allowPrivate;

    @org.springframework.beans.factory.annotation.Autowired
    public UrlPolicy(WanProperties props) {
        this(props.security().allowLoopbackDownloads(), props.security().allowPrivateNetworkDownloads());
    }

    public UrlPolicy(boolean allowLoopback, boolean allowPrivate) {
        this.allowLoopback = allowLoopback;
        this.allowPrivate = allowPrivate;
    }

    /** Syntax-only validation (no DNS). */
    public URI parse(String raw) {
        if (raw == null || raw.isBlank()) throw WdmException.badRequest("url_required", "A URL is required");
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_URL_LENGTH) throw WdmException.badRequest("url_too_long", "URL is too long");
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw WdmException.badRequest("url_invalid", "Not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw WdmException.badRequest("url_scheme", "Only http and https URLs can be downloaded");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) throw WdmException.badRequest("url_host", "URL has no host");
        if (uri.getRawUserInfo() != null) throw WdmException.badRequest("url_userinfo", "URLs with embedded credentials are refused");
        return uri.normalize();
    }

    /** Full validation including the resolved addresses. */
    public URI validate(String raw) {
        URI uri = parse(raw);
        checkHost(uri.getHost());
        return uri;
    }

    public void checkHost(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(stripBrackets(host));
        } catch (UnknownHostException e) {
            throw WdmException.badRequest("url_unresolvable", "Host could not be resolved: " + host);
        }
        for (InetAddress a : addresses) checkAddress(a);
    }

    void checkAddress(InetAddress a) {
        if (a.isLoopbackAddress()) {
            if (!allowLoopback) throw WdmException.forbidden("url_loopback", "Downloads from this computer's own services are blocked");
            return;
        }
        if (a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isMulticastAddress()) {
            throw WdmException.forbidden("url_reserved", "Downloads from reserved network addresses are blocked");
        }
        boolean uniqueLocalV6 = a instanceof Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc;
        if ((a.isSiteLocalAddress() || uniqueLocalV6 || isCgnat(a)) && !allowPrivate) {
            throw WdmException.forbidden("url_private", "Downloads from private network addresses are disabled in settings");
        }
    }

    private static boolean isCgnat(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }
}
