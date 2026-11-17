package com.wan.downloadmanager.controller;

import com.wan.downloadmanager.browser.BrowserService;
import com.wan.downloadmanager.dto.DownloadView;
import com.wan.downloadmanager.media.MediaService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Called by the native messaging host on behalf of a browser extension (same bearer token as the UI). */
@RestController
@RequestMapping("/api/v1/browser")
public class BrowserController {
    private final BrowserService browsers;
    private final MediaService media;

    public BrowserController(BrowserService browsers, MediaService media) {
        this.browsers = browsers;
        this.media = media;
    }

    @GetMapping("/status")
    public List<BrowserService.BrowserStatus> status() {
        return browsers.status();
    }

    @PostMapping("/connect")
    public BrowserService.ExtensionConfig connect(@RequestBody BrowserService.Hello hello) {
        return browsers.hello(hello);
    }

    @PostMapping("/media")
    public List<MediaService.SourceView> media(@Valid @RequestBody MediaService.Report report) {
        return media.detect(report);
    }

    @PostMapping("/download")
    public DownloadView download(@RequestBody BrowserService.Handoff handoff) {
        return browsers.handoff(handoff);
    }
}
