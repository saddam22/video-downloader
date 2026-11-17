package com.wan.downloadmanager.identity;

import java.util.Optional;

/** OS-backed secret storage for refresh tokens and the offline-cache signing key. Never SQLite, never logs. */
public interface CredentialVault {
    void put(String name, String secret);

    Optional<String> get(String name);

    void delete(String name);

    /** True when secrets survive a restart (OS store); false for the in-memory fallback. */
    boolean persistent();

    String description();
}
