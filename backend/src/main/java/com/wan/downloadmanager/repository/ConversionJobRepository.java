package com.wan.downloadmanager.repository;

import com.wan.downloadmanager.entity.ConversionJob;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversionJobRepository extends JpaRepository<ConversionJob, String> {
    java.util.List<ConversionJob> findAllByOrderByCreatedAtDesc();
    java.util.List<ConversionJob> findByStatus(com.wan.downloadmanager.entity.JobStatus status);
}
