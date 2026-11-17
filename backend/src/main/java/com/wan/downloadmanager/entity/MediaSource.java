package com.wan.downloadmanager.entity;

import jakarta.persistence.*;

/** A media resource the browser extension saw on a page. */
@Entity
@Table(name = "media_sources")
public class MediaSource {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "page_url")
    private String pageUrl;
    @Column(name = "source_url")
    private String sourceUrl;
    @Column(name = "title")
    private String title;
    @Column(name = "mime_type")
    private String mimeType;
    @Enumerated(EnumType.STRING)
    @Column(name = "kind")
    private MediaKind kind;
    @Column(name = "duration_sec")
    private Double durationSec;
    @Column(name = "thumbnail")
    private String thumbnail;
    @Column(name = "browser")
    private String browser;
    @Column(name = "fingerprint")
    private String fingerprint;
    @Column(name = "detected_at")
    private long detectedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPageUrl() { return pageUrl; }
    public void setPageUrl(String pageUrl) { this.pageUrl = pageUrl; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public MediaKind getKind() { return kind; }
    public void setKind(MediaKind kind) { this.kind = kind; }
    public Double getDurationSec() { return durationSec; }
    public void setDurationSec(Double durationSec) { this.durationSec = durationSec; }
    public String getThumbnail() { return thumbnail; }
    public void setThumbnail(String thumbnail) { this.thumbnail = thumbnail; }
    public String getBrowser() { return browser; }
    public void setBrowser(String browser) { this.browser = browser; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public long getDetectedAt() { return detectedAt; }
    public void setDetectedAt(long detectedAt) { this.detectedAt = detectedAt; }
}
