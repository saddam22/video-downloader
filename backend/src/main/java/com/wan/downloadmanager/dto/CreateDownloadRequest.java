package com.wan.downloadmanager.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * New download. Only {@code url} is required. {@code headers} may carry Referer / Accept-Language
 * only; credentials (Cookie, Authorization) are refused.
 */
public record CreateDownloadRequest(
        @NotBlank @Size(max = 8192) String url,
        @Size(max = 255) String fileName,
        @Size(max = 1024) String saveDir,
        @Min(1) @Max(16) Integer connections,
        String queueId,
        Boolean startNow,
        @Size(max = 8192) String pageUrl,
        Map<String, String> headers,
        Boolean allowDuplicate,
        String source) {

    public static CreateDownloadRequest of(String url) {
        return new CreateDownloadRequest(url, null, null, null, null, true, null, null, null, null);
    }
}
