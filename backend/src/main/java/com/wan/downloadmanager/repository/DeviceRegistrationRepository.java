package com.wan.downloadmanager.repository;

import com.wan.downloadmanager.entity.DeviceRegistration;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRegistrationRepository extends JpaRepository<DeviceRegistration, String> {
}
