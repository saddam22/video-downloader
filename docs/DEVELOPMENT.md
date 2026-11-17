# Development

Requirements: JDK 21, Maven 3.9, Node 22, FFmpeg on PATH (media tests), optional PostgreSQL + the
WAN Identity Hub checkout for the identity E2E.

```bash
mvn -B install                                   # backend, native host, desktop + all Java tests
npm --prefix browser-extensions ci
npm --prefix browser-extensions test             # build + unit tests
java -jar desktop/target/wdm-desktop.jar         # run the app (uses your real data folder)
java -Dwan.data-dir=/tmp/wdm -jar desktop/target/wdm-desktop.jar --wan.data-dir=/tmp/wdm   # isolated
java -jar desktop/target/wdm-desktop.jar --screenshot-tour=shots   # renders every screen to PNG, then exits
mvn -f backend/pom.xml spring-boot:run           # backend only (API on a random port, see runtime.json)
```

Point at a local hub: add `--wan.identity.issuer=http://localhost:4100/oidc --wan.identity.client-id=wan-download-manager`.

## Tests (state on 2026-11-17)
| Suite | Count | Notes |
|---|---|---|
| Backend (`mvn -f backend/pom.xml test`) | 55 pass, 1 skipped | skipped = `HubEndToEndTest` (opt-in, needs a hub) |
| Native host | 4 pass | |
| Extensions (`node --test`) | 3 pass | |
| Hub E2E (`WAN_HUB_ISSUER=… HubEndToEndTest`) | 1 pass | against patched hub @1a5d221 |
| Browser E2E (`xvfb-run node scripts/e2e-browser.mjs`) | pass | Chromium only |
| Desktop | screenshot tour renders all 16 screens without exceptions | no TestFX suite yet |

Engine tests run against `MockHttpServer` (ranges, ETags, redirects, slow and dropped connections);
media tests generate their fixtures with FFmpeg's `testsrc`/`sine` — no third-party content or network.

## Loading unpacked extensions
Chrome/Edge: `chrome://extensions` → Developer mode → Load unpacked → `browser-extensions/dist/chrome`
(or `edge`); then `scripts/install-dev.ps1 -ChromeId <id>`. Firefox: `about:debugging` → Load temporary
add-on → `dist/firefox/manifest.json`.

## Conventions
Services own logic; controllers are thin; UI never blocks the FX thread (`Wan.background`); every
premium check goes through `EntitlementService`; every outbound URL through `UrlPolicy`; nothing
user-visible claims progress, sizes or qualities that were not measured.
