package com.wan.downloadmanager.entitlement;

/** Product capabilities that a plan may include. Keys match the entitlement catalog. */
public enum Feature {
    DOWNLOAD_BASIC("download.basic"),
    DOWNLOAD_RESUME("download.resume"),
    DOWNLOAD_MULTITHREAD("download.multithread"),
    DOWNLOAD_BATCH("download.batch"),
    DOWNLOAD_SCHEDULER("download.scheduler"),
    BROWSER_INTEGRATION("browser.integration"),
    VIDEO_DETECTION("video.detection"),
    VIDEO_QUALITY("video.quality"),
    MEDIA_CONVERSION("media.conversion"),
    BUSINESS_LICENSE("business.license"),
    CLOUD_SYNC("cloud.sync");

    private final String key;

    Feature(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Feature fromKey(String key) {
        for (Feature f : values()) if (f.key.equals(key)) return f;
        throw new IllegalArgumentException("Unknown feature " + key);
    }
}
