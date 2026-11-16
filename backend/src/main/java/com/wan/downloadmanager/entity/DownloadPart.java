package com.wan.downloadmanager.entity;

import jakarta.persistence.*;

/** A byte range of a segmented download. */
@Entity
@Table(name = "download_parts")
public class DownloadPart {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "download_id")
    private String downloadId;
    @Column(name = "part_index")
    private int partIndex;
    @Column(name = "start_byte")
    private long startByte;
    @Column(name = "end_byte")
    private Long endByte;
    @Column(name = "downloaded_bytes")
    private long downloadedBytes;
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private PartStatus status;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDownloadId() { return downloadId; }
    public void setDownloadId(String downloadId) { this.downloadId = downloadId; }
    public int getPartIndex() { return partIndex; }
    public void setPartIndex(int partIndex) { this.partIndex = partIndex; }
    public long getStartByte() { return startByte; }
    public void setStartByte(long startByte) { this.startByte = startByte; }
    public Long getEndByte() { return endByte; }
    public void setEndByte(Long endByte) { this.endByte = endByte; }
    public long getDownloadedBytes() { return downloadedBytes; }
    public void setDownloadedBytes(long downloadedBytes) { this.downloadedBytes = downloadedBytes; }
    public PartStatus getStatus() { return status; }
    public void setStatus(PartStatus status) { this.status = status; }
}
