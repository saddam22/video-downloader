package com.wan.downloadmanager.repository;

import com.wan.downloadmanager.entity.DownloadQueue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DownloadQueueRepository extends JpaRepository<DownloadQueue, String> {
    java.util.List<DownloadQueue> findAllByOrderByPriorityDescNameAsc();
}
