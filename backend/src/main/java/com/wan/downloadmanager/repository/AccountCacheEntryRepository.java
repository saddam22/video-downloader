package com.wan.downloadmanager.repository;

import com.wan.downloadmanager.entity.AccountCacheEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountCacheEntryRepository extends JpaRepository<AccountCacheEntry, String> {
}
