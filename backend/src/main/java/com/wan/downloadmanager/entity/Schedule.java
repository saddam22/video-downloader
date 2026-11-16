package com.wan.downloadmanager.entity;

import jakarta.persistence.*;

/** A download window that starts (and optionally stops) a queue. */
@Entity
@Table(name = "schedules")
public class Schedule {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "name")
    private String name;
    @Column(name = "queue_id")
    private String queueId;
    @Column(name = "start_time")
    private String startTime;
    @Column(name = "end_time")
    private String endTime;
    @Column(name = "days_of_week")
    private String daysOfWeek;
    @Column(name = "one_shot_date")
    private String oneShotDate;
    @Column(name = "speed_limit_bps")
    private long speedLimitBps;
    @Column(name = "enabled")
    private boolean enabled;
    @Column(name = "last_run_at")
    private Long lastRunAt;
    @Column(name = "created_at")
    private long createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getQueueId() { return queueId; }
    public void setQueueId(String queueId) { this.queueId = queueId; }
    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }
    public String getDaysOfWeek() { return daysOfWeek; }
    public void setDaysOfWeek(String daysOfWeek) { this.daysOfWeek = daysOfWeek; }
    public String getOneShotDate() { return oneShotDate; }
    public void setOneShotDate(String oneShotDate) { this.oneShotDate = oneShotDate; }
    public long getSpeedLimitBps() { return speedLimitBps; }
    public void setSpeedLimitBps(long speedLimitBps) { this.speedLimitBps = speedLimitBps; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Long getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(Long lastRunAt) { this.lastRunAt = lastRunAt; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
