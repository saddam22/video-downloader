package com.wan.downloadmanager.controller;

import com.wan.downloadmanager.dto.DownloadView;
import com.wan.downloadmanager.entity.ConversionJob;
import com.wan.downloadmanager.media.ConversionService;
import com.wan.downloadmanager.media.FfmpegService;
import com.wan.downloadmanager.media.MediaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/media")
public class MediaController {
    private final MediaService media;
    private final ConversionService conversions;
    private final FfmpegService ffmpeg;

    public MediaController(MediaService media, ConversionService conversions, FfmpegService ffmpeg) {
        this.media = media;
        this.conversions = conversions;
        this.ffmpeg = ffmpeg;
    }

    @GetMapping("/sources")
    public List<MediaService.SourceView> sources() {
        return media.list();
    }

    @PostMapping("/detect")
    public List<MediaService.SourceView> detect(@Valid @RequestBody MediaService.Report report) {
        return media.detect(report);
    }

    @GetMapping("/sources/{id}")
    public MediaService.SourceView source(@PathVariable String id) {
        return media.get(id);
    }

    @DeleteMapping("/sources")
    public ResponseEntity<Void> clear() {
        media.clear();
        return ResponseEntity.noContent().build();
    }

    public record DownloadFormat(@NotBlank String formatId) {}

    @PostMapping("/sources/{id}/download")
    public DownloadView download(@PathVariable String id, @Valid @RequestBody DownloadFormat body) {
        return media.download(id, body.formatId());
    }

    public record ConvertRequest(@NotBlank String inputPath, @NotBlank String format, String outputDir) {}

    @PostMapping("/convert")
    public ConversionJob convert(@Valid @RequestBody ConvertRequest req) {
        return conversions.submit(req.inputPath(), req.format(), req.outputDir());
    }

    @GetMapping("/conversions")
    public List<ConversionJob> conversions() {
        return conversions.list();
    }

    @PostMapping("/conversions/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        conversions.cancel(id);
        return Map.of("ok", true);
    }

    @GetMapping("/ffmpeg")
    public Map<String, Object> ffmpeg() {
        Map<String, Object> m = new LinkedHashMap<>();
        ffmpeg.detect().ifPresentOrElse(t -> {
            m.put("available", true);
            m.put("ffmpeg", t.ffmpeg());
            m.put("ffprobe", t.ffprobe());
            m.put("version", t.version());
        }, () -> m.put("available", false));
        return m;
    }
}
