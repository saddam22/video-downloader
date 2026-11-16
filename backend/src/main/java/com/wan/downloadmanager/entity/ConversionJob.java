package com.wan.downloadmanager.entity;

import jakarta.persistence.*;

/** An FFmpeg conversion job. */
@Entity
@Table(name = "conversion_jobs")
public class ConversionJob {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "input_path")
    private String inputPath;
    @Column(name = "output_path")
    private String outputPath;
    @Column(name = "preset")
    private String preset;
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private JobStatus status;
    @Column(name = "progress")
    private double progress;
    @Column(name = "error_message")
    private String errorMessage;
    @Column(name = "created_at")
    private long createdAt;
    @Column(name = "updated_at")
    private long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInputPath() { return inputPath; }
    public void setInputPath(String inputPath) { this.inputPath = inputPath; }
    public String getOutputPath() { return outputPath; }
    public void setOutputPath(String outputPath) { this.outputPath = outputPath; }
    public String getPreset() { return preset; }
    public void setPreset(String preset) { this.preset = preset; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }
    public double getProgress() { return progress; }
    public void setProgress(double progress) { this.progress = progress; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
