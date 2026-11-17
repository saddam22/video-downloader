package com.wan.downloadmanager.repository;

import com.wan.downloadmanager.entity.Download;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DownloadRepository extends JpaRepository<Download, String> {
    java.util.List<Download> findByStatusIn(java.util.Collection<com.wan.downloadmanager.entity.DownloadStatus> statuses);
    java.util.List<Download> findByUrl(String url);
    java.util.List<Download> findAllByOrderByCreatedAtDesc();
    long countByStatus(com.wan.downloadmanager.entity.DownloadStatus status);
}
