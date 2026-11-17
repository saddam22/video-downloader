package com.wan.downloadmanager.controller;

import com.wan.downloadmanager.entitlement.EntitlementService;
import com.wan.downloadmanager.identity.IdentityService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local application routes about the signed-in WAN account. These are NOT WAN Identity Hub
 * endpoints; the hub is only reached through its discovered OIDC endpoints.
 */
@RestController
@RequestMapping("/api/v1/account")
public class AccountController {
    private final IdentityService identity;
    private final EntitlementService entitlements;

    public AccountController(IdentityService identity, EntitlementService entitlements) {
        this.identity = identity;
        this.entitlements = entitlements;
    }

    @GetMapping
    public IdentityService.AccountView account() {
        return identity.account();
    }

    @GetMapping("/profile")
    public Object profile() {
        var a = identity.account();
        return a.profile() == null ? Map.of("status", a.status()) : a.profile();
    }

    /** The plan in force and where it came from. There is no price catalog (WAN has no billing service yet). */
    @GetMapping("/subscription")
    public Map<String, Object> subscription() {
        var st = entitlements.state();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tier", st.tier());
        m.put("source", st.source());
        m.put("hubStatus", st.hubStatus());
        m.put("roles", st.roles());
        m.put("verifiedAt", st.verifiedAt());
        m.put("offlineUntil", st.offlineUntil());
        m.put("billingUrl", identity.account().billingUrl());
        m.put("prices", null);
        return m;
    }

    @GetMapping("/entitlements")
    public Map<String, Object> entitlements() {
        var plan = entitlements.plan();
        return Map.of("tier", entitlements.state().tier(),
                "features", plan.features().stream().map(f -> f.key()).sorted().toList(),
                "maxConnections", plan.maxConnections(), "maxConcurrent", plan.maxConcurrent());
    }

    @PostMapping("/login")
    public IdentityService.LoginStart login(@RequestParam(defaultValue = "false") boolean signup) {
        return identity.startLogin(signup);
    }

    @PostMapping("/login/cancel")
    public Map<String, Object> cancel() {
        identity.cancelLogin();
        return Map.of("ok", true);
    }

    @PostMapping("/refresh")
    public IdentityService.AccountView refresh() {
        identity.reverify();
        return identity.account();
    }

    @PostMapping("/logout")
    public IdentityService.LogoutResult logout() {
        return identity.logout();
    }
}
