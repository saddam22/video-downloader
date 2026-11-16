package com.wan.downloadmanager.entity;

import jakarta.persistence.*;

/** A verified format/quality of a media source. */
@Entity
@Table(name = "media_formats")
public class MediaFormat {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "media_source_id")
    private String mediaSourceId;
    @Column(name = "format_url")
    private String formatUrl;
    @Column(name = "container")
    private String container;
    @Column(name = "width")
    private Integer width;
    @Column(name = "height")
    private Integer height;
    @Column(name = "bandwidth")
    private Long bandwidth;
    @Column(name = "video_codec")
    private String videoCodec;
    @Column(name = "audio_codec")
    private String audioCodec;
    @Column(name = "estimated_bytes")
    private Long estimatedBytes;
    @Column(name = "label")
    private String label;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMediaSourceId() { return mediaSourceId; }
    public void setMediaSourceId(String mediaSourceId) { this.mediaSourceId = mediaSourceId; }
    public String getFormatUrl() { return formatUrl; }
    public void setFormatUrl(String formatUrl) { this.formatUrl = formatUrl; }
    public String getContainer() { return container; }
    public void setContainer(String container) { this.container = container; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }
    public Long getBandwidth() { return bandwidth; }
    public void setBandwidth(Long bandwidth) { this.bandwidth = bandwidth; }
    public String getVideoCodec() { return videoCodec; }
    public void setVideoCodec(String videoCodec) { this.videoCodec = videoCodec; }
    public String getAudioCodec() { return audioCodec; }
    public void setAudioCodec(String audioCodec) { this.audioCodec = audioCodec; }
    public Long getEstimatedBytes() { return estimatedBytes; }
    public void setEstimatedBytes(Long estimatedBytes) { this.estimatedBytes = estimatedBytes; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}
