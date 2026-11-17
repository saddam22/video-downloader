package com.wan.downloadmanager.repository;

import com.wan.downloadmanager.entity.BrowserSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrowserSessionRepository extends JpaRepository<BrowserSession, String> {
    java.util.List<BrowserSession> findTop10ByOrderByLastSeenAtDesc();
}
