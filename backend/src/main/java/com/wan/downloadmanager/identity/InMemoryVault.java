package com.wan.downloadmanager.identity;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fallback where no supported OS store exists (Linux/macOS development, CI): secrets live only in
 * memory, so the user signs in again after a restart and the offline cache cannot be trusted
 * across restarts (its signing key is gone) — the safe failure mode.
 */
public class InMemoryVault implements CredentialVault {
    private final Map<String, String> secrets = new ConcurrentHashMap<>();

    @Override public void put(String name, String secret) { secrets.put(name, secret); }
    @Override public Optional<String> get(String name) { return Optional.ofNullable(secrets.get(name)); }
    @Override public void delete(String name) { secrets.remove(name); }
    @Override public boolean persistent() { return false; }
    @Override public String description() { return "In-memory only (no supported OS credential store on this platform)"; }
}
