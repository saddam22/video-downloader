# Project status — 2026-11-17

**State: feature-complete development build, not production-ready.** Core download engine, identity,
media, browser integration and desktop UI work and are tested on Linux; Windows packaging and several
WAN-side dependencies are outstanding (see KNOWN-ISSUES.md).

| Phase | Result |
|---|---|
| 0 Ecosystem audit | Done: WAN-IDENTITY-AUDIT.md, WAN-DESIGN-SYSTEM-AUDIT.md, integration map (ARCHITECTURE.md), hub patch |
| 1 Foundation | Done: Maven multi-module, Spring Boot 3.5, SQLite + Flyway (18 tables), local API security |
| 2 Design system | Done: JavaFX CSS tokens dark/light/system, component library |
| 3 Identity | Done against real hub + patch; production registration pending |
| 4 Download engine | Done: ranges, 1–16 connections, resume with validators, retry, bandwidth limit |
| 5 Desktop UI | Done: 16 screens, tray, single instance |
| 6 Browser extensions | Done: Chrome/Edge/Firefox MV3 builds + native host; Chromium E2E |
| 7 Video & audio | Done: file/HLS detection, single-file DASH, HLS download + remux, conversion |
| 8 Advanced | Done: batch, queues, scheduler, categories, speed limits |
| 9 Commercial | Partial: entitlements + plans UI; no billing/devices (external) |
| 10 QA & release | Partial: tests + docs; installer not built here, unsigned |

## Test results (last run)
Backend 55 passed / 1 skipped (opt-in hub E2E) · Native host 4 passed · Extensions 3 passed ·
Hub E2E 1 passed (local patched hub) · Browser E2E passed (Chromium) · Hub suite with patch 85/85 + 19/19.

## Bugs found and fixed by tests
- Final file named `x (1).ext` because the job's own partial file blocked the name.
- Ternary unboxing NPE in download view progress.
- Conversion cancel race (process started after cancel) and cancel reported as failure.
- Media reports from `CHROME`/`FIREFOX` rejected by a 4-character field limit (found by the browser E2E).

## Next steps
1. Hub owner: review and merge the desktop-client patch; register production client.
2. Build and smoke-test the MSI on Windows (DPAPI, tray, native host registration, Edge/Firefox).
3. Code-signing certificate; publish extensions.
4. Segmented DASH, HLS resume, Bengali strings, TestFX suite.
