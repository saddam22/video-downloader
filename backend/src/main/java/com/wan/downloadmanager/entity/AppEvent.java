package com.wan.downloadmanager.entity;

import jakarta.persistence.*;

/** Structured application/audit event (redacted). */
@Entity
@Table(name = "application_events")
public class AppEvent {

    @Id
    @Column(name = "id")
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "level")
    private EventLevel level;
    @Column(name = "category")
    private String category;
    @Column(name = "message")
    private String message;
    @Column(name = "subject_id")
    private String subjectId;
    @Column(name = "created_at")
    private long createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public EventLevel getLevel() { return level; }
    public void setLevel(EventLevel level) { this.level = level; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String subjectId) { this.subjectId = subjectId; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
