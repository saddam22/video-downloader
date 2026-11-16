package com.wan.downloadmanager.repository;

import com.wan.downloadmanager.entity.DownloadHeader;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DownloadHeaderRepository extends JpaRepository<DownloadHeader, String> {
    java.util.List<DownloadHeader> findByDownloadId(String downloadId);
    @org.springframework.transaction.annotation.Transactional void deleteByDownloadId(String downloadId);
}
