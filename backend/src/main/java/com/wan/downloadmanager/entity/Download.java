package com.wan.downloadmanager.entity;

import jakarta.persistence.*;

/** One download and its persisted state (the recovery source of truth). */
@Entity
@Table(name = "downloads")
public class Download {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "url")
    private String url;
    @Column(name = "final_url")
    private String finalUrl;
    @Column(name = "page_url")
    private String pageUrl;
    @Column(name = "file_name")
    private String fileName;
    @Column(name = "save_dir")
    private String saveDir;
    @Column(name = "temp_path")
    private String tempPath;
    @Column(name = "category_id")
    private String categoryId;
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private DownloadStatus status;
    @Column(name = "total_bytes")
    private Long totalBytes;
    @Column(name = "downloaded_bytes")
    private long downloadedBytes;
    @Column(name = "mime_type")
    private String mimeType;
    @Column(name = "etag")
    private String etag;
    @Column(name = "last_modified")
    private String lastModified;
    @Column(name = "resumable")
    private boolean resumable;
    @Column(name = "connections")
    private int connections;
    @Column(name = "speed_limit_bps")
    private long speedLimitBps;
    @Column(name = "retry_count")
    private int retryCount;
    @Column(name = "max_retries")
    private int maxRetries;
    @Column(name = "error_message")
    private String errorMessage;
    @Column(name = "sha256")
    private String sha256;
    @Enumerated(EnumType.STRING)
    @Column(name = "source")
    private DownloadSource source;
    @Column(name = "priority")
    private int priority;
    @Column(name = "created_at")
    private long createdAt;
    @Column(name = "updated_at")
    private long updatedAt;
    @Column(name = "started_at")
    private Long startedAt;
    @Column(name = "completed_at")
    private Long completedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getFinalUrl() { return finalUrl; }
    public void setFinalUrl(String finalUrl) { this.finalUrl = finalUrl; }
    public String getPageUrl() { return pageUrl; }
    public void setPageUrl(String pageUrl) { this.pageUrl = pageUrl; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getSaveDir() { return saveDir; }
    public void setSaveDir(String saveDir) { this.saveDir = saveDir; }
    public String getTempPath() { return tempPath; }
    public void setTempPath(String tempPath) { this.tempPath = tempPath; }
    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }
    public DownloadStatus getStatus() { return status; }
    public void setStatus(DownloadStatus status) { this.status = status; }
    public Long getTotalBytes() { return totalBytes; }
    public void setTotalBytes(Long totalBytes) { this.totalBytes = totalBytes; }
    public long getDownloadedBytes() { return downloadedBytes; }
    public void setDownloadedBytes(long downloadedBytes) { this.downloadedBytes = downloadedBytes; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }
    public String getLastModified() { return lastModified; }
    public void setLastModified(String lastModified) { this.lastModified = lastModified; }
    public boolean isResumable() { return resumable; }
    public void setResumable(boolean resumable) { this.resumable = resumable; }
    public int getConnections() { return connections; }
    public void setConnections(int connections) { this.connections = connections; }
    public long getSpeedLimitBps() { return speedLimitBps; }
    public void setSpeedLimitBps(long speedLimitBps) { this.speedLimitBps = speedLimitBps; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public DownloadSource getSource() { return source; }
    public void setSource(DownloadSource source) { this.source = source; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public Long getStartedAt() { return startedAt; }
    public void setStartedAt(Long startedAt) { this.startedAt = startedAt; }
    public Long getCompletedAt() { return completedAt; }
    public void setCompletedAt(Long completedAt) { this.completedAt = completedAt; }
}
