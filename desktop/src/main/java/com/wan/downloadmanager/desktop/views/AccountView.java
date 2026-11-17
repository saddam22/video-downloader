package com.wan.downloadmanager.desktop.views;

import com.wan.downloadmanager.desktop.MainShell;
import com.wan.downloadmanager.desktop.ui.Icons;
import com.wan.downloadmanager.desktop.ui.Wan;
import com.wan.downloadmanager.entitlement.EntitlementService;
import com.wan.downloadmanager.entitlement.Feature;
import com.wan.downloadmanager.identity.IdentityService;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.*;

import java.time.Instant;
import java.util.Locale;

/**
 * WAN Identity Hub account: sign-in (system browser, PKCE), profile, plan and its features, licence
 * source (online/offline cache), this device, sign-out. Plans show features only — no invented prices.
 */
public class AccountView implements MainShell.View {
    private final MainShell shell;
    private final VBox page = new VBox(16);

    public AccountView(MainShell shell) {
        this.shell = shell;
    }

    @Override
    public Node build() {
        page.getStyleClass().add("page");
        refresh();
        return page;
    }

    public void refresh() {
        var s = shell.services();
        IdentityService.AccountView a = s.identity().account();
        page.getChildren().clear();
        switch (a.status()) {
            case NOT_CONFIGURED -> page.getChildren().add(Wan.banner("warn",
                    "WAN sign-in is not configured for this installation (no WAN Identity Hub address). Downloads work without an account on the Free plan."));
            case SIGNING_IN -> page.getChildren().add(Wan.banner("info", "Finish signing in in your browser…",
                    Wan.button("Cancel", Wan.Variant.SECONDARY, null, () -> s.identity().cancelLogin())));
            case OFFLINE -> page.getChildren().add(Wan.banner("warn", a.lastError() == null ? "Offline" : a.lastError(),
                    Wan.button("Retry now", Wan.Variant.SECONDARY, Icons.RETRY, this::reverify)));
            default -> {
                if (a.lastError() != null) page.getChildren().add(Wan.banner("err", a.lastError()));
            }
        }
        page.getChildren().add(a.profile() == null ? signedOut(a) : profile(a));
        page.getChildren().add(plans(a));
        if (a.device() != null) {
            page.getChildren().add(Wan.card(Wan.text("License and devices", "h2"),
                    kv("This device", a.device().name()), kv("Installation ID", a.device().installationId()),
                    kv("Credential storage", a.vault() + (a.vaultPersistent() ? "" : " — you sign in again after a restart")),
                    Wan.text(a.device().note(), "muted", "small")));
        }
    }

    private Node signedOut(IdentityService.AccountView a) {
        var s = shell.services();
        Label title = Wan.text("Sign in with your WAN account", "h1");
        title.setStyle("-fx-text-fill: white;");
        Label body = Wan.text("One account for every WAN platform. You sign in on WAN Identity Hub in your browser — "
                + "WAN Download Manager never sees your password.", "");
        Wan.Variant v = Wan.Variant.PRIMARY;
        HBox actions = new HBox(10,
                Wan.button("Continue to WAN Login", v, Icons.USER, () -> login(false)),
                Wan.button("Create account", Wan.Variant.SECONDARY, Icons.PLUS, () -> login(true)));
        if (a.hubWebUrl() != null) actions.getChildren().add(Wan.button("Forgot password", Wan.Variant.GHOST, null,
                () -> shell.host().showDocument(a.hubWebUrl() + "/#/forgot-password")));
        actions.getChildren().forEach(n -> n.setDisable(!a.configured() || a.status() == IdentityService.Status.SIGNING_IN));
        VBox hero = new VBox(10, MainShell.logo(48), title, body, actions,
                Wan.text("By signing in you agree to the WAN Terms of Service and Privacy Policy.", "small"));
        hero.getStyleClass().add("hero-card");
        if (a.loginUrl() != null) hero.getChildren().add(Wan.text("Browser did not open? Visit: " + a.loginUrl(), "small"));
        return hero;
    }

    private Node profile(IdentityService.AccountView a) {
        var p = a.profile();
        Label avatar = new Label(initials(p.name()));
        avatar.getStyleClass().add("avatar");
        avatar.setMinSize(64, 64);
        avatar.setStyle("-fx-font-size: 22px;");
        VBox info = new VBox(4, Wan.text(p.name() == null ? "WAN user" : p.name(), "h1"),
                Wan.text((p.email() == null ? "" : p.email()) + (Boolean.TRUE.equals(p.emailVerified()) ? "  ✓ verified" : ""), "muted"),
                Wan.text(p.phone() == null ? "" : p.phone() + (Boolean.TRUE.equals(p.phoneVerified()) ? "  ✓ verified" : ""), "muted"),
                Wan.text("Account ID " + p.sub(), "muted", "small"));
        HBox.setHgrow(info, Priority.ALWAYS);
        VBox right = new VBox(8, Wan.chip(a.status() == IdentityService.Status.OFFLINE ? "Offline" : "Active", a.status() == IdentityService.Status.OFFLINE ? "warn" : "ok"),
                Wan.button("Refresh", Wan.Variant.SECONDARY, Icons.RETRY, this::reverify),
                Wan.button("Sign out", Wan.Variant.DANGER, null, this::logout));
        if (a.hubWebUrl() != null) right.getChildren().add(1, Wan.button("Manage account", Wan.Variant.SECONDARY, Icons.LINK,
                () -> shell.host().showDocument(a.hubWebUrl() + "/#/profile")));
        right.setAlignment(Pos.TOP_RIGHT);
        HBox h = new HBox(16, avatar, info, right);
        return Wan.card(h);
    }

    private Node plans(IdentityService.AccountView a) {
        var e = shell.services().entitlements();
        EntitlementService.State st = a.entitlement();
        HBox cards = new HBox(14);
        for (EntitlementService.Tier t : EntitlementService.Tier.values()) {
            var plan = e.plan(t);
            boolean current = t == st.tier();
            VBox c = Wan.card(new HBox(8, Wan.text(cap(t.name()), "h2"), Wan.spacer(), current ? Wan.chip("Current plan", "ok") : new Region()),
                    Wan.text(switch (t) {
                        case FREE -> "Everything you need for everyday downloads.";
                        case PRO -> "Acceleration, video grabbing, conversion and automation.";
                        case BUSINESS -> "Commercial use with centrally managed licences.";
                    }, "muted"),
                    Wan.text("Price: set by WAN billing (not available yet)", "small", "muted"));
            for (Feature f : Feature.values()) {
                if (f == Feature.CLOUD_SYNC) continue;
                boolean has = plan.features().contains(f);
                if (has) c.getChildren().add(new HBox(8, Icons.icon(Icons.DONE, 14, javafx.scene.paint.Color.web("#16a34a")), Wan.text(featureName(f), "body")));
            }
            c.getChildren().add(Wan.text("Up to " + plan.maxConnections() + " connection(s) per download, " + plan.maxConcurrent() + " at a time", "muted", "small"));
            if (!current && t != EntitlementService.Tier.FREE) {
                var up = Wan.button("Upgrade", Wan.Variant.PRIMARY, null, () -> shell.host().showDocument(a.billingUrl()));
                up.setDisable(a.billingUrl() == null);
                if (a.billingUrl() == null) up.setTooltip(new javafx.scene.control.Tooltip("WAN billing is not available yet"));
                c.getChildren().add(up);
            }
            if (current) c.setStyle("-fx-border-color: -wan-primary; -fx-border-width: 2;");
            HBox.setHgrow(c, Priority.ALWAYS);
            cards.getChildren().add(c);
        }
        String source = switch (st.source()) {
            case ONLINE -> "Verified with WAN Identity Hub" + (st.verifiedAt() == null ? "" : " at " + Instant.ofEpochMilli(st.verifiedAt()));
            case OFFLINE_CACHE -> st.offlineUntil() == null ? "Offline" : "Offline licence valid until " + Instant.ofEpochMilli(st.offlineUntil());
            case NOT_CONFIGURED -> "Free plan (WAN sign-in not configured)";
            case SIGNED_OUT -> "Free plan — sign in to use your WAN plan";
        };
        return new VBox(10, new HBox(10, Wan.text("Subscription", "h2"), Wan.spacer(), Wan.text(source, "muted")), cards);
    }

    static String featureName(Feature f) {
        return switch (f) {
            case DOWNLOAD_BASIC -> "File downloads";
            case DOWNLOAD_RESUME -> "Pause and resume";
            case DOWNLOAD_MULTITHREAD -> "Multi-connection acceleration";
            case DOWNLOAD_BATCH -> "Batch downloads";
            case DOWNLOAD_SCHEDULER -> "Scheduler";
            case BROWSER_INTEGRATION -> "Browser integration";
            case VIDEO_DETECTION -> "Video detection";
            case VIDEO_QUALITY -> "Quality selection";
            case MEDIA_CONVERSION -> "Media conversion";
            case BUSINESS_LICENSE -> "Commercial-use licence";
            case CLOUD_SYNC -> "Cloud sync";
        };
    }

    private void login(boolean signup) {
        Wan.background(() -> shell.services().identity().startLogin(signup), r -> refresh(), e -> {
            shell.toast("error", "Sign-in", e.getMessage());
            refresh();
        });
    }

    private void reverify() {
        Wan.background(() -> {
            shell.services().identity().reverify();
            return true;
        }, ok -> refresh(), e -> shell.toast("error", "Account", e.getMessage()));
    }

    private void logout() {
        if (!Wan.confirm(shell.stage(), "Sign out?", "Downloads continue on the Free plan. Your sign-in on this computer is revoked.", "Sign out")) return;
        Wan.background(() -> shell.services().identity().logout(), r -> {
            refresh();
            shell.toast("success", "Signed out", r.revoked() ? "Session revoked at WAN Identity Hub" : "Signed out on this computer");
        }, e -> shell.toast("error", "Sign out", e.getMessage()));
    }

    private static Node kv(String k, String v) {
        HBox h = new HBox(10, Wan.text(k, "field-label"), Wan.spacer(), Wan.text(v, "body"));
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    static String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] p = name.trim().split("\\s+");
        return (p[0].substring(0, 1) + (p.length > 1 ? p[p.length - 1].substring(0, 1) : "")).toUpperCase(Locale.ROOT);
    }

    static String cap(String s) {
        return s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}
