package com.wan.downloadmanager.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Guards every local endpoint:
 * <ul>
 *   <li>Host must be the loopback address we bound to (defeats DNS rebinding);</li>
 *   <li>any Origin header is refused — legitimate callers (desktop UI, native host) are not
 *       browsers, so a request carrying Origin comes from a web page (defeats CSRF; there is no CORS);</li>
 *   <li>a valid bearer token is required ({@code Authorization: Bearer} or, for WebSocket
 *       handshakes only, {@code ?access_token=}).</li>
 * </ul>
 * Only {@code /actuator/health} is open (it reveals nothing beyond "UP").
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalApiFilter extends OncePerRequestFilter {
    private static final Pattern LOOPBACK_HOST = Pattern.compile("^(127\\.0\\.0\\.1|localhost|\\[::1])(:\\d{1,5})?$", Pattern.CASE_INSENSITIVE);

    private final LocalApiAuth auth;

    public LocalApiFilter(LocalApiAuth auth) {
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String host = req.getHeader("Host");
        if (host == null || !LOOPBACK_HOST.matcher(host).matches()) {
            deny(res, 403, "host_not_allowed");
            return;
        }
        if (req.getHeader("Origin") != null) {
            deny(res, 403, "origin_not_allowed");
            return;
        }
        String path = req.getRequestURI();
        if (path.equals("/actuator/health")) {
            chain.doFilter(req, res);
            return;
        }
        String presented = null;
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) presented = header.substring(7).trim();
        if (presented == null && path.startsWith("/ws/")) presented = req.getParameter("access_token");
        if (!auth.matches(presented)) {
            deny(res, 401, "unauthorized");
            return;
        }
        chain.doFilter(req, res);
    }

    private static void deny(HttpServletResponse res, int status, String code) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":{\"code\":\"" + code + "\"}}");
    }
}
