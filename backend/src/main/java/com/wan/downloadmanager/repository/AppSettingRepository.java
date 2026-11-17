package com.wan.downloadmanager.repository;

import com.wan.downloadmanager.entity.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
