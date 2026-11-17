package com.wan.downloadmanager.entity;

import jakarta.persistence.*;

/** A non-sensitive request header stored with a download. */
@Entity
@Table(name = "download_headers")
public class DownloadHeader {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "download_id")
    private String downloadId;
    @Column(name = "name")
    private String name;
    @Column(name = "value")
    private String value;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDownloadId() { return downloadId; }
    public void setDownloadId(String downloadId) { this.downloadId = downloadId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
