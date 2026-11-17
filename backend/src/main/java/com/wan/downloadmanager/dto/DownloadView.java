package com.wan.downloadmanager.dto;

/** A download as shown to the UI and API. Speed and ETA come from bytes actually received. */
public record DownloadView(
        String id,
        String url,
        String pageUrl,
        String fileName,
        String saveDir,
        String filePath,
        String categoryId,
        String queueId,
        String status,
        Long totalBytes,
        long downloadedBytes,
        Double progress,
        long speedBps,
        Long etaSeconds,
        int connections,
        int activeConnections,
        boolean resumable,
        String errorMessage,
        String source,
        String mimeType,
        String sha256,
        long createdAt,
        Long completedAt) {
}
