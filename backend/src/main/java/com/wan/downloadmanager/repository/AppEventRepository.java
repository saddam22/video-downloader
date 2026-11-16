package com.wan.downloadmanager.repository;

import com.wan.downloadmanager.entity.AppEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppEventRepository extends JpaRepository<AppEvent, String> {
    java.util.List<AppEvent> findTop200ByOrderByCreatedAtDesc();
}
