package com.wan.downloadmanager.security;

import com.wan.downloadmanager.exception.WdmException;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Filename sanitising, path-traversal protection and overwrite protection. */
public final class SafeFiles {
    private SafeFiles() {}

    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");
    private static final Pattern ILLEGAL = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
    private static final Pattern CD_FILENAME_STAR = Pattern.compile("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CD_FILENAME = Pattern.compile("filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)", Pattern.CASE_INSENSITIVE);
    public static final int MAX_NAME = 180;

    /** Makes any string a safe single path component on Windows, macOS and Linux. */
    public static String sanitize(String name) {
        if (name == null) name = "";
        String s = ILLEGAL.matcher(name).replaceAll("_").strip();
        s = s.replaceAll("^[.\\s]+", "").replaceAll("[.\\s]+$", "");
        if (s.isEmpty() || s.equals("..")) s = "download";
        String base = s.contains(".") ? s.substring(0, s.indexOf('.')) : s;
        if (WINDOWS_RESERVED.contains(base.toLowerCase(Locale.ROOT))) s = "_" + s;
        if (s.length() > MAX_NAME) {
            String ext = extension(s);
            int keep = MAX_NAME - (ext.isEmpty() ? 0 : ext.length() + 1);
            s = s.substring(0, keep) + (ext.isEmpty() || ext.length() > 16 ? "" : "." + ext);
        }
        return s;
    }

    /** Lower-case extension without the dot, or "" . */
    public static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "";
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.length() <= 16 && ext.chars().allMatch(Character::isLetterOrDigit) ? ext : "";
    }

    /** Filename from Content-Disposition (RFC 6266/5987), falling back to the URL path. */
    public static String fileNameFrom(String contentDisposition, URI uri) {
        if (contentDisposition != null) {
            Matcher star = CD_FILENAME_STAR.matcher(contentDisposition);
            if (star.find()) {
                String charset = star.group(1).isBlank() ? "UTF-8" : star.group(1).trim();
                try {
                    return sanitize(URLDecoder.decode(star.group(2).trim(), charset));
                } catch (Exception ignored) {
                    // fall through to plain filename
                }
            }
            Matcher plain = CD_FILENAME.matcher(contentDisposition);
            if (plain.find()) {
                String v = plain.group(2) != null ? plain.group(2) : plain.group(1);
                return sanitize(v.trim());
            }
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        last = URLDecoder.decode(last.replace("+", "%2B"), StandardCharsets.UTF_8);
        return sanitize(last.isBlank() ? uri.getHost() : last);
    }

    /**
     * Resolves {@code name} inside {@code dir}, refusing anything that escapes it, and returns a
     * path that does not exist yet ("name (1).ext", "name (2).ext", …) — existing files are never overwritten.
     */
    public static Path uniqueTarget(Path dir, String name) {
        Path root = dir.toAbsolutePath().normalize();
        String safe = sanitize(name);
        Path candidate = inside(root, safe);
        String ext = extension(safe);
        String stem = ext.isEmpty() ? safe : safe.substring(0, safe.length() - ext.length() - 1);
        for (int i = 1; Files.exists(candidate) || Files.exists(candidate.resolveSibling(candidate.getFileName() + ".wdmpart")); i++) {
            if (i > 9999) throw WdmException.conflict("file_exists", "Too many files named " + safe);
            candidate = inside(root, stem + " (" + i + ")" + (ext.isEmpty() ? "" : "." + ext));
        }
        return candidate;
    }

    /** Resolves a single component inside root or throws (path traversal protection). */
    public static Path inside(Path root, String component) {
        Path resolved = root.resolve(component).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw WdmException.badRequest("path_traversal", "Invalid file name");
        }
        return resolved;
    }

    public static void ensureDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw WdmException.badRequest("dir_unavailable", "Cannot use folder " + dir + ": " + e.getMessage());
        }
    }
}
