package com.wan.downloadmanager.repository;

import com.wan.downloadmanager.entity.DownloadCategory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DownloadCategoryRepository extends JpaRepository<DownloadCategory, String> {
    java.util.List<DownloadCategory> findAllByOrderByName();
}
