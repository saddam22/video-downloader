package com.wan.downloadmanager.entity;

import jakarta.persistence.*;

/** A browser extension connection seen through native messaging. */
@Entity
@Table(name = "browser_sessions")
public class BrowserSession {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "browser")
    private String browser;
    @Column(name = "extension_ver")
    private String extensionVer;
    @Column(name = "connected_at")
    private long connectedAt;
    @Column(name = "last_seen_at")
    private long lastSeenAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getBrowser() { return browser; }
    public void setBrowser(String browser) { this.browser = browser; }
    public String getExtensionVer() { return extensionVer; }
    public void setExtensionVer(String extensionVer) { this.extensionVer = extensionVer; }
    public long getConnectedAt() { return connectedAt; }
    public void setConnectedAt(long connectedAt) { this.connectedAt = connectedAt; }
    public long getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(long lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
