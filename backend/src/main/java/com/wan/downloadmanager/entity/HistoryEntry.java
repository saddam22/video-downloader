package com.wan.downloadmanager.entity;

import jakarta.persistence.*;

/** Finished/failed download record kept independently of the download row. */
@Entity
@Table(name = "download_history")
public class HistoryEntry {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "download_id")
    private String downloadId;
    @Column(name = "file_name")
    private String fileName;
    @Column(name = "file_type")
    private String fileType;
    @Column(name = "url")
    private String url;
    @Column(name = "size_bytes")
    private Long sizeBytes;
    @Column(name = "status")
    private String status;
    @Column(name = "save_path")
    private String savePath;
    @Column(name = "completed_at")
    private long completedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDownloadId() { return downloadId; }
    public void setDownloadId(String downloadId) { this.downloadId = downloadId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSavePath() { return savePath; }
    public void setSavePath(String savePath) { this.savePath = savePath; }
    public long getCompletedAt() { return completedAt; }
    public void setCompletedAt(long completedAt) { this.completedAt = completedAt; }
}
