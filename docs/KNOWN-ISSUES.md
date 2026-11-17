# Known issues and gaps

Honest list of what is not done or not proven. Items marked **external** need someone outside this repository.

## Requires external action
1. **external** — WAN Identity Hub has no public-client support in `main`. The patch
   `docs/integrations/wan-identity-hub-desktop-client.patch` (tested: hub 85/85 + 19/19) must be applied and deployed.
2. **external** — No production issuer/client registered; the app ships with sign-in disabled until configured.
3. **external** — No WAN billing / subscription catalog / payment service: no prices, Upgrade button disabled. Tier roles are set by the hub admin.
4. **external** — No device registration API in the hub: device limits cannot be enforced; installation tracked locally.
5. **external** — No code-signing certificate: installer and EXEs are unsigned (SmartScreen warning).
6. **external** — Extensions not published; store ids unknown (dev ids via `allowed-origins.properties`).
7. **external** — Offline grace period is a local setting (7 days); the hub has no licensing policy to supply it.

## Not verified in this environment
8. The Windows MSI was **not built** here (Linux container, no WiX/Windows). `scripts/package.ps1` is written but unexecuted.
9. DPAPI vault, Windows tray, Startup shortcut, system-theme detection were compiled but **not run on Windows**.
10. Edge and Firefox extensions were built but **not run in those browsers** (Chromium E2E only).
11. No automated desktop UI tests (TestFX); the UI was checked with the screenshot tour and manually reviewed.

## Functional limitations
12. Segmented DASH (`SegmentTemplate`/`SegmentList`) is not downloadable — such sources say so instead of offering formats.
13. HLS downloads cannot resume mid-stream: pause/stop restarts the stream; live streams are refused.
14. HLS "size" during download is extrapolated from completed segments (progress itself is the real segment count); final size is exact.
15. Downloads that need browser cookies/login are not supported (cookies are never copied, by design).
16. Bengali UI strings are not translated yet (language setting stored, English shown).
17. Second-instance launch shows a message instead of focusing the running window or forwarding a URL.
18. "Start with Windows" writes a Startup-folder shortcut only when run from the installed EXE.
19. Bandwidth scheduling changes the global limit while a schedule window is open; overlapping windows are not merged.
20. DNS rebinding between the SSRF address check and the connection is mitigated (re-check per hop) but not eliminated (would need pinning resolved addresses into the HTTP client).
21. Dependency vulnerability scanning is not in CI.
