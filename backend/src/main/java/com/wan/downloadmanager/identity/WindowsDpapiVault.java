package com.wan.downloadmanager.identity;

import com.sun.jna.platform.win32.Crypt32Util;
import com.wan.downloadmanager.config.FilePermissions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Windows Data Protection API (DPAPI, CurrentUser scope): each secret is encrypted with a key
 * derived from the user's Windows logon credentials and stored as a blob in the per-user data
 * folder. Only the same Windows user on the same machine can decrypt it — the same protection the
 * Windows Credential Manager uses for generic credentials.
 */
public class WindowsDpapiVault implements CredentialVault {
    private static final Pattern NAME = Pattern.compile("[a-z0-9._-]{1,64}");
    private static final byte[] ENTROPY = "WAN-Download-Manager/vault/v1".getBytes(StandardCharsets.UTF_8);
    private final Path dir;

    public WindowsDpapiVault(Path dataDir) {
        this.dir = dataDir.resolve("vault");
        try {
            Files.createDirectories(dir);
            FilePermissions.restrictToOwner(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void put(String name, String secret) {
        byte[] blob = Crypt32Util.cryptProtectData(secret.getBytes(StandardCharsets.UTF_8), ENTROPY, 0, "WAN Download Manager", null);
        Path file = file(name);
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, blob);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<String> get(String name) {
        Path file = file(name);
        if (!Files.exists(file)) return Optional.empty();
        try {
            byte[] plain = Crypt32Util.cryptUnprotectData(Files.readAllBytes(file), ENTROPY, 0, null);
            return Optional.of(new String(plain, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Optional.empty(); // another user/machine, or corrupted: treat as absent
        }
    }

    @Override
    public void delete(String name) {
        try {
            Files.deleteIfExists(file(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean persistent() {
        return true;
    }

    @Override
    public String description() {
        return "Windows Data Protection API (current user)";
    }

    private Path file(String name) {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("bad vault entry name");
        return dir.resolve(name + ".dpapi");
    }
}
