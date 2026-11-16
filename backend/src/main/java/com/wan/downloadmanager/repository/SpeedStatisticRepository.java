package com.wan.downloadmanager.repository;

import com.wan.downloadmanager.entity.SpeedStatistic;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpeedStatisticRepository extends JpaRepository<SpeedStatistic, String> {
    java.util.Optional<SpeedStatistic> findByBucketAt(long bucketAt);
    java.util.List<SpeedStatistic> findByBucketAtGreaterThanEqualOrderByBucketAt(long from);
    @org.springframework.data.jpa.repository.Query("select coalesce(sum(s.bytes), 0) from SpeedStatistic s") long totalBytes();
}
