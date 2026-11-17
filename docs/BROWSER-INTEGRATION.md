# Browser integration

One TypeScript code base (`browser-extensions/shared`) builds three Manifest V3 extensions:
`npm --prefix browser-extensions run build` → `dist/chrome`, `dist/edge`, `dist/firefox`.

| | Chrome / Edge | Firefox |
|---|---|---|
| Background | service worker (module) | event page (`background.scripts`, module) |
| Min version | Chrome 116 | Firefox 128 (`download-manager@wan.example`) |
| Native host | registry `...\Google\Chrome\` / `...\Microsoft\Edge\NativeMessagingHosts` | `...\Mozilla\NativeMessagingHosts` (`allowed_extensions`) |

## Detection
1. **Content script** (on request via activeTab "Scan page", or on all sites after the user grants the
   optional site permission): reads `<video>/<audio>` `currentSrc`, `<source>` URLs and types, and the
   decoded `videoWidth/videoHeight/duration/poster`. It reads nothing else.
2. **Network**: `webRequest.onHeadersReceived` notices `.m3u8`/`.mpd` manifests and `video/*`/`audio/*`
   responses (segments ignored).
3. Sources are de-duplicated per tab (URL without fragment), the badge shows the count, an optional
   floating notice (closed shadow root) opens the grabber.
4. "Get formats" sends the source to the app (`media` message); the app verifies it (HTTP probe /
   manifest parse) and returns only real formats. `blob:` media-source URLs cannot be downloaded.
5. "Download" sends `media-download {sourceId, formatId}`; the app queues it.

Other hand-offs: context menu "Download with WAN Download Manager" (links, video, audio); popup
"Download a link"; optional interception of browser downloads (off by default, optional `downloads`
permission; if the app refuses — e.g. the site needs the user's login cookies — the browser download resumes).

## Messages (extension → host), version 1
`hello{version}` · `status` · `ping` · `download{url, fileName?, pageUrl?, mimeType?}` ·
`media{pageUrl, title?, sources[≤50]}` · `media-download{sourceId, formatId}`; all carry `id`, `v:1`.
Replies: `{id, result}` or `{id, error:{code,message}}` (`app_not_running`, `host_missing`, `invalid_message`, …).

## Verified end to end
`scripts/e2e-browser.mjs` (Chromium + unpacked extension + native host + backend + real WAN Identity
Hub as Pro) passed on 2026-11-16: detection, badge, verified format, download byte-identical to source.
Edge and Firefox builds are produced but were not run in a real browser here (no Edge/Firefox in the
build environment) — see KNOWN-ISSUES.

## Diagnostics
App → Browser Integration shows per-browser last contact, runtime file and allow-list presence.
Extension popup shows "Connected / not installed / app not running".
