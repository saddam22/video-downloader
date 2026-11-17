package com.wan.downloadmanager.repository;

import com.wan.downloadmanager.entity.DownloadPart;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DownloadPartRepository extends JpaRepository<DownloadPart, String> {
    java.util.List<DownloadPart> findByDownloadIdOrderByPartIndex(String downloadId);
    @org.springframework.transaction.annotation.Transactional void deleteByDownloadId(String downloadId);
}
