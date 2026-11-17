package com.wan.downloadmanager.entity;

import jakarta.persistence.*;

/** This installation. */
@Entity
@Table(name = "device_registration_cache")
public class DeviceRegistration {

    @Id
    @Column(name = "installation_id")
    private String installationId;

    @Column(name = "device_name")
    private String deviceName;
    @Column(name = "registered_with")
    private String registeredWith;
    @Column(name = "last_verified_at")
    private Long lastVerifiedAt;
    @Column(name = "created_at")
    private long createdAt;

    public String getInstallationId() { return installationId; }
    public void setInstallationId(String installationId) { this.installationId = installationId; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public String getRegisteredWith() { return registeredWith; }
    public void setRegisteredWith(String registeredWith) { this.registeredWith = registeredWith; }
    public Long getLastVerifiedAt() { return lastVerifiedAt; }
    public void setLastVerifiedAt(Long lastVerifiedAt) { this.lastVerifiedAt = lastVerifiedAt; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
