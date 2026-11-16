package com.wan.downloadmanager.download;

import com.wan.downloadmanager.dto.CreateDownloadRequest;
import com.wan.downloadmanager.entitlement.EntitlementService;
import com.wan.downloadmanager.entitlement.Feature;
import com.wan.downloadmanager.exception.WdmException;
import com.wan.downloadmanager.security.UrlPolicy;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Many URLs at once (pasted text or a TXT file): validated, de-duplicated, queued. */
@Service
public class BatchService {
    public static final int MAX_URLS = 1000;

    public record Line(int line, String url, String status, String message, String downloadId) {}

    public record Result(int added, int duplicates, int invalid, List<Line> lines) {}

    private final DownloadService downloads;
    private final UrlPolicy policy;
    private final EntitlementService entitlements;

    public BatchService(DownloadService downloads, UrlPolicy policy, EntitlementService entitlements) {
        this.downloads = downloads;
        this.policy = policy;
        this.entitlements = entitlements;
    }

    /** Validates without adding: what would happen for each line. */
    public Result preview(String text) {
        return run(text, null, null, false);
    }

    public Result add(String text, String queueId, String saveDir) {
        entitlements.require(Feature.DOWNLOAD_BATCH);
        return run(text, queueId, saveDir, true);
    }

    private Result run(String text, String queueId, String saveDir, boolean commit) {
        if (text == null) throw WdmException.badRequest("empty", "No URLs given");
        String[] raw = text.split("\\R");
        if (raw.length > MAX_URLS * 2) throw WdmException.badRequest("too_many", "At most " + MAX_URLS + " URLs per batch");
        List<Line> lines = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int added = 0, dups = 0, invalid = 0;
        for (int i = 0; i < raw.length; i++) {
            String s = raw[i].strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            if (seen.size() >= MAX_URLS) throw WdmException.badRequest("too_many", "At most " + MAX_URLS + " URLs per batch");
            URI uri;
            try {
                uri = policy.parse(s);
            } catch (WdmException e) {
                lines.add(new Line(i + 1, s, "INVALID", e.getMessage(), null));
                invalid++;
                continue;
            }
            if (!seen.add(uri.toString())) {
                lines.add(new Line(i + 1, s, "DUPLICATE", "Repeated in this batch", null));
                dups++;
                continue;
            }
            if (!commit) {
                lines.add(new Line(i + 1, uri.toString(), "OK", null, null));
                continue;
            }
            try {
                var view = downloads.create(new CreateDownloadRequest(uri.toString(), null, saveDir, null, queueId, true, null, null, false, "BATCH"));
                lines.add(new Line(i + 1, uri.toString(), "ADDED", null, view.id()));
                added++;
            } catch (DuplicateDownloadException e) {
                lines.add(new Line(i + 1, uri.toString(), "DUPLICATE", e.getMessage(), e.existingId()));
                dups++;
            } catch (WdmException e) {
                lines.add(new Line(i + 1, uri.toString(), "INVALID", e.getMessage(), null));
                invalid++;
            }
        }
        return new Result(added, dups, invalid, lines);
    }
}
