package com.wan.downloadmanager.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Owner-only permissions on POSIX file systems. On Windows the per-user %LOCALAPPDATA% folder is
 * already restricted to the user by its inherited ACL, so nothing is changed there.
 */
public final class FilePermissions {
    private FilePermissions() {}

    public static void restrictToOwner(Path path) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) == null) return;
        String perms = Files.isDirectory(path) ? "rwx------" : "rw-------";
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms));
    }
}
