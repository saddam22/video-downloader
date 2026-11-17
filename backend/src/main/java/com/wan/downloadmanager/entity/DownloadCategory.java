package com.wan.downloadmanager.entity;

import jakarta.persistence.*;

/** Category used to sort finished files into sub-folders. */
@Entity
@Table(name = "download_categories")
public class DownloadCategory {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "name")
    private String name;
    @Column(name = "extensions")
    private String extensions;
    @Column(name = "subfolder")
    private String subfolder;
    @Column(name = "built_in")
    private boolean builtIn;
    @Column(name = "created_at")
    private long createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getExtensions() { return extensions; }
    public void setExtensions(String extensions) { this.extensions = extensions; }
    public String getSubfolder() { return subfolder; }
    public void setSubfolder(String subfolder) { this.subfolder = subfolder; }
    public boolean isBuiltIn() { return builtIn; }
    public void setBuiltIn(boolean builtIn) { this.builtIn = builtIn; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
