package com.wan.downloadmanager.repository;

import com.wan.downloadmanager.entity.EntitlementCacheEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntitlementCacheEntryRepository extends JpaRepository<EntitlementCacheEntry, String> {
}
