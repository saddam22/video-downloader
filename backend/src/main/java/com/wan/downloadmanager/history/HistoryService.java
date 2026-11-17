package com.wan.downloadmanager.history;

import com.wan.downloadmanager.entity.HistoryEntry;
import com.wan.downloadmanager.repository.HistoryEntryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class HistoryService {
    private final HistoryEntryRepository repo;

    public HistoryService(HistoryEntryRepository repo) {
        this.repo = repo;
    }

    public List<HistoryEntry> search(String query, String status, int limit) {
        String like = query == null || query.isBlank() ? null : "%" + query.toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
        String st = status == null || status.isBlank() ? null : status.toUpperCase(Locale.ROOT);
        return repo.search(like, st, PageRequest.of(0, Math.max(1, Math.min(limit, 5000))));
    }

    /** RFC 4180 CSV; cells starting with = + - @ are prefixed with ' (spreadsheet formula injection). */
    public String exportCsv(String query, String status) {
        StringBuilder sb = new StringBuilder("file_name,file_type,url,size_bytes,status,save_path,completed_at\r\n");
        for (HistoryEntry h : search(query, status, 5000)) {
            sb.append(cell(h.getFileName())).append(',').append(cell(h.getFileType())).append(',').append(cell(h.getUrl())).append(',')
                    .append(h.getSizeBytes() == null ? "" : h.getSizeBytes()).append(',').append(cell(h.getStatus())).append(',')
                    .append(cell(h.getSavePath())).append(',').append(Instant.ofEpochMilli(h.getCompletedAt())).append("\r\n");
        }
        return sb.toString();
    }

    public void clear() {
        repo.deleteAll();
    }

    static String cell(String v) {
        if (v == null) return "";
        String s = v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
