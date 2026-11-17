package com.wan.downloadmanager.repository;

import com.wan.downloadmanager.entity.MediaSource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaSourceRepository extends JpaRepository<MediaSource, String> {
    java.util.Optional<MediaSource> findByFingerprint(String fingerprint);
    java.util.List<MediaSource> findTop200ByOrderByDetectedAtDesc();
}
