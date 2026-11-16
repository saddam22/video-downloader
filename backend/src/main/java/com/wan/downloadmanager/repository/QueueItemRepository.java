package com.wan.downloadmanager.repository;

import com.wan.downloadmanager.entity.QueueItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QueueItemRepository extends JpaRepository<QueueItem, String> {
    java.util.List<QueueItem> findByQueueIdOrderByPosition(String queueId);
    java.util.Optional<QueueItem> findByDownloadId(String downloadId);
    @org.springframework.transaction.annotation.Transactional void deleteByDownloadId(String downloadId);
    @org.springframework.data.jpa.repository.Query("select coalesce(max(q.position), 0) from QueueItem q where q.queueId = ?1") int maxPosition(String queueId);
}
