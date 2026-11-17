package com.wan.downloadmanager.entitlement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wan.downloadmanager.exception.WdmException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The single place that decides what the current user may use. Every premium check in the
 * backend and UI goes through {@link #has(Feature)} / {@link #require(Feature)}.
 *
 * <p>The tier is set only by the identity layer from <em>verified</em> data: a validated ID token /
 * userinfo claim ({@code wan_entitlement}) or the HMAC-signed offline cache. Signed-out, unknown or
 * suspended → FREE. Local settings never influence it.
 */
@Service
public class EntitlementService {
    public enum Tier { FREE, PRO, BUSINESS }

    public enum Source { SIGNED_OUT, ONLINE, OFFLINE_CACHE, NOT_CONFIGURED }

    public record Plan(Set<Feature> features, int maxConnections, int maxConcurrent) {}

    public record State(Tier tier, Source source, String hubStatus, List<String> roles, Long verifiedAt, Long offlineUntil) {
        public static State free(Source source) {
            return new State(Tier.FREE, source, "none", List.of(), null, null);
        }
    }

    private final Map<Tier, Plan> catalog;
    private final List<Consumer<State>> listeners = new CopyOnWriteArrayList<>();
    private volatile State state = State.free(Source.SIGNED_OUT);

    public EntitlementService(ObjectMapper mapper) {
        this.catalog = load(mapper);
    }

    private static Map<Tier, Plan> load(ObjectMapper mapper) {
        try (InputStream in = EntitlementService.class.getResourceAsStream("/entitlements/catalog.json")) {
            JsonNode tiers = mapper.readTree(in).get("tiers");
            Map<Tier, Plan> out = new EnumMap<>(Tier.class);
            for (Tier t : Tier.values()) {
                JsonNode n = tiers.get(t.name());
                Set<Feature> fs = EnumSet.noneOf(Feature.class);
                n.get("features").forEach(f -> fs.add(Feature.fromKey(f.asText())));
                out.put(t, new Plan(Set.copyOf(fs), n.get("maxConnections").asInt(), n.get("maxConcurrent").asInt()));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Maps hub roles to a tier: highest of business > pro > free. Unknown roles are ignored. */
    public static Tier tierFromRoles(String hubStatus, List<String> roles) {
        if (!"active".equals(hubStatus)) return Tier.FREE;
        Tier best = Tier.FREE;
        for (String r : roles) {
            String role = r.toLowerCase(Locale.ROOT).trim();
            if (role.equals("business")) return Tier.BUSINESS;
            if (role.equals("pro")) best = Tier.PRO;
        }
        return best;
    }

    public State state() {
        return state;
    }

    public Plan plan() {
        return catalog.get(state.tier());
    }

    public Plan plan(Tier tier) {
        return catalog.get(tier);
    }

    public boolean has(Feature f) {
        return plan().features().contains(f);
    }

    public void require(Feature f) {
        if (!has(f)) throw WdmException.entitlement(f.key());
    }

    public int clampConnections(int requested) {
        return Math.max(1, Math.min(requested, plan().maxConnections()));
    }

    public int clampConcurrent(int requested) {
        return Math.max(1, Math.min(requested, plan().maxConcurrent()));
    }

    /** Called by the identity layer only. */
    public void apply(State next) {
        this.state = next;
        listeners.forEach(l -> l.accept(next));
    }

    public void onChange(Consumer<State> listener) {
        listeners.add(listener);
    }
}
