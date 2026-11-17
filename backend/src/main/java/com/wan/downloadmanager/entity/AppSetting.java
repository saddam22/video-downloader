package com.wan.downloadmanager.entity;

import jakarta.persistence.*;

/** A persisted application setting (key/value). */
@Entity
@Table(name = "app_settings")
public class AppSetting {
    @Id
    @Column(name = "key")
    private String key;
    @Column(name = "value")
    private String value;
    @Column(name = "updated_at")
    private long updatedAt;

    public AppSetting() {}
    public AppSetting(String key, String value, long updatedAt) { this.key = key; this.value = value; this.updatedAt = updatedAt; }
    public String getKey() { return key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
