package com.wan.downloadmanager.entity;

import jakarta.persistence.*;

/** Membership and order of a download in a queue. */
@Entity
@Table(name = "queue_items")
public class QueueItem {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "queue_id")
    private String queueId;
    @Column(name = "download_id")
    private String downloadId;
    @Column(name = "position")
    private int position;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getQueueId() { return queueId; }
    public void setQueueId(String queueId) { this.queueId = queueId; }
    public String getDownloadId() { return downloadId; }
    public void setDownloadId(String downloadId) { this.downloadId = downloadId; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
}
