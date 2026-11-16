package com.wan.downloadmanager.entity;

import jakarta.persistence.*;

/** Per-minute throughput bucket. */
@Entity
@Table(name = "speed_statistics")
public class SpeedStatistic {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "bucket_at")
    private long bucketAt;
    @Column(name = "bytes")
    private long bytes;
    @Column(name = "peak_bps")
    private long peakBps;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public long getBucketAt() { return bucketAt; }
    public void setBucketAt(long bucketAt) { this.bucketAt = bucketAt; }
    public long getBytes() { return bytes; }
    public void setBytes(long bytes) { this.bytes = bytes; }
    public long getPeakBps() { return peakBps; }
    public void setPeakBps(long peakBps) { this.peakBps = peakBps; }
}
