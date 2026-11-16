package com.wan.downloadmanager.download;

import com.wan.downloadmanager.entity.DownloadCategory;
import com.wan.downloadmanager.repository.DownloadCategoryRepository;
import com.wan.downloadmanager.security.SafeFiles;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;

/** Picks a category from the file extension (falls back to the MIME type, then "Other"). */
@Component
public class Categorizer {
    public static final String OTHER = "cat-other";
    private final DownloadCategoryRepository categories;

    public Categorizer(DownloadCategoryRepository categories) {
        this.categories = categories;
    }

    public DownloadCategory categorize(String fileName, String mimeType) {
        String ext = SafeFiles.extension(fileName);
        var all = categories.findAll();
        if (!ext.isEmpty()) {
            for (DownloadCategory c : all) {
                if (Arrays.stream(c.getExtensions().split(",")).map(String::trim).anyMatch(ext::equals)) return c;
            }
        }
        if (mimeType != null) {
            String m = mimeType.toLowerCase(Locale.ROOT);
            String id = m.startsWith("video/") ? "cat-video" : m.startsWith("audio/") ? "cat-music"
                    : m.startsWith("image/") ? "cat-images" : m.equals("application/pdf") ? "cat-documents"
                    : m.contains("zip") || m.contains("compressed") ? "cat-compressed"
                    : m.equals("application/vnd.android.package-archive") ? "cat-apk" : null;
            if (id != null) return categories.findById(id).orElse(null);
        }
        return categories.findById(OTHER).orElse(null);
    }
}
