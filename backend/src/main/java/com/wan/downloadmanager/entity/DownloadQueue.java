package com.wan.downloadmanager.entity;

import jakarta.persistence.*;

/** A named queue with its own concurrency limit. */
@Entity
@Table(name = "download_queues")
public class DownloadQueue {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "name")
    private String name;
    @Column(name = "priority")
    private int priority;
    @Column(name = "max_concurrent")
    private int maxConcurrent;
    @Enumerated(EnumType.STRING)
    @Column(name = "state")
    private QueueState state;
    @Column(name = "built_in")
    private boolean builtIn;
    @Column(name = "created_at")
    private long createdAt;
    @Column(name = "updated_at")
    private long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
    public QueueState getState() { return state; }
    public void setState(QueueState state) { this.state = state; }
    public boolean isBuiltIn() { return builtIn; }
    public void setBuiltIn(boolean builtIn) { this.builtIn = builtIn; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
