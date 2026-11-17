package com.wan.downloadmanager.repository;

import com.wan.downloadmanager.entity.MediaFormat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaFormatRepository extends JpaRepository<MediaFormat, String> {
    java.util.List<MediaFormat> findByMediaSourceId(String mediaSourceId);
    @org.springframework.transaction.annotation.Transactional void deleteByMediaSourceId(String mediaSourceId);
}
