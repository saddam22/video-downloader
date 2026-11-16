package com.wan.downloadmanager.controller;

import com.wan.downloadmanager.download.BatchService;
import com.wan.downloadmanager.download.DownloadService;
import com.wan.downloadmanager.dto.CreateDownloadRequest;
import com.wan.downloadmanager.dto.DownloadView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/downloads")
public class DownloadController {
    private final DownloadService downloads;
    private final BatchService batch;

    public DownloadController(DownloadService downloads, BatchService batch) {
        this.downloads = downloads;
        this.batch = batch;
    }

    /** {@code ?filter=all|active|completed|failed} */
    @GetMapping
    public List<DownloadView> list(@RequestParam(required = false) String filter) {
        return downloads.list(filter);
    }

    @PostMapping
    public ResponseEntity<DownloadView> create(@Valid @RequestBody CreateDownloadRequest req) {
        return ResponseEntity.status(201).body(downloads.create(req));
    }

    @GetMapping("/{id}")
    public DownloadView get(@PathVariable String id) {
        return downloads.find(id);
    }

    @PostMapping("/{id}/pause")
    public DownloadView pause(@PathVariable String id) {
        return downloads.pause(id);
    }

    @PostMapping("/{id}/resume")
    public DownloadView resume(@PathVariable String id) {
        return downloads.resume(id);
    }

    @PostMapping("/{id}/retry")
    public DownloadView retry(@PathVariable String id) {
        return downloads.retry(id);
    }

    @PostMapping("/{id}/cancel")
    public DownloadView cancel(@PathVariable String id) throws InterruptedException {
        return downloads.cancel(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @RequestParam(defaultValue = "false") boolean deleteFile)
            throws InterruptedException {
        downloads.delete(id, deleteFile);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/pause-all")
    public Map<String, Object> pauseAll() {
        downloads.pauseAll();
        return Map.of("ok", true);
    }

    @PostMapping("/resume-all")
    public Map<String, Object> resumeAll() {
        downloads.resumeAll();
        return Map.of("ok", true);
    }

    @PostMapping("/retry-failed")
    public Map<String, Object> retryFailed() {
        return Map.of("retried", downloads.retryFailed());
    }

    public record BatchRequest(@Size(max = 2_000_000) String text, String queueId, String saveDir, Boolean preview) {}

    @PostMapping("/batch")
    public BatchService.Result batch(@RequestBody BatchRequest req) {
        return Boolean.TRUE.equals(req.preview()) ? batch.preview(req.text()) : batch.add(req.text(), req.queueId(), req.saveDir());
    }
}
