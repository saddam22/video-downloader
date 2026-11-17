# Changelog

## 0.1.0 — development build (2026-11-17)
- Download engine: HTTP/HTTPS, byte ranges with up to 16 connections, resume validated by ETag/Last-Modified/size, If-Range, retry with backoff, global/per-download bandwidth limits, overwrite protection, SHA-256.
- Queues, batch import, scheduler, categories, duplicate detection, history with CSV export, dashboard statistics.
- WAN Identity Hub sign-in (OIDC code + PKCE, loopback), DPAPI vault, signed offline entitlement cache, Free/Pro/Business feature catalog.
- Media: verified detection (file, HLS, single-file DASH), HLS download + FFmpeg remux/merge, conversion queue; DRM/encrypted/live streams refused.
- Browser extensions (Chrome, Edge, Firefox MV3) and native messaging host.
- JavaFX desktop app with WAN design system (dark/light), 16 screens, tray, single instance.
- Proposed WAN Identity Hub patch for public desktop clients.
