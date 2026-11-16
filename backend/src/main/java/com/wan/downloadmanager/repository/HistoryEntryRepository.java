package com.wan.downloadmanager.repository;

import com.wan.downloadmanager.entity.HistoryEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HistoryEntryRepository extends JpaRepository<HistoryEntry, String> {
    @org.springframework.data.jpa.repository.Query("select h from HistoryEntry h where (?1 is null or lower(h.fileName) like ?1 or lower(h.url) like ?1) and (?2 is null or h.status = ?2) order by h.completedAt desc") java.util.List<HistoryEntry> search(String likeLower, String status, org.springframework.data.domain.Pageable page);
}
