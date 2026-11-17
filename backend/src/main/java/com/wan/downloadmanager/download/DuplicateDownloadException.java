package com.wan.downloadmanager.download;

import com.wan.downloadmanager.exception.WdmException;

/** The URL is already in the list; the caller may retry with {@code allowDuplicate=true}. */
public class DuplicateDownloadException extends WdmException {
    private final String existingId;

    public DuplicateDownloadException(String existingId, String fileName, String status) {
        super(409, "duplicate", "Already in your downloads: " + fileName + " (" + status.toLowerCase() + ")");
        this.existingId = existingId;
    }

    public String existingId() {
        return existingId;
    }
}
