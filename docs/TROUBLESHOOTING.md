# Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Extension popup: "native host not found" | Host not registered for this browser: rerun `scripts/register-native-host.ps1` (installer step), restart the browser |
| Popup: "WAN Download Manager is not running" | Start the app (it can stay in the tray) |
| Popup: "origin_not_allowed" | Unpacked/dev extension id not in `allowed-origins.properties` (data folder) |
| "Downloads from this computer's own services are blocked" | SSRF protection for `localhost`; intended. LAN addresses are allowed unless disabled in config |
| Download restarts from 0 after resume | The server's ETag/Last-Modified/size changed, or it has no Range support — the file on the server is different, so old bytes are discarded |
| Only one connection used | Free plan, or server without byte ranges, or file < 1 MB |
| A site's download fails with 401/403 | It needs your browser login; WAN never copies browser cookies. Download it in the browser |
| Video found but "Protected (encrypted) stream" | DRM/encrypted streams are not downloaded, by design |
| "Segmented DASH is not supported yet" | Only single-file DASH representations are supported (KNOWN-ISSUES) |
| HLS result is `.ts` | FFmpeg not found; install it to get MP4 |
| Sign-in: "does not accept desktop (public) clients" | The WAN Identity Hub lacks the desktop-client patch |
| Sign-in times out | Finish within 5 minutes in the opened browser tab; Account → Cancel and retry |
| Signed out after restart (non-Windows dev) | In-memory vault by design on non-Windows |
| "already running" message | One instance per user; use the tray icon |
| Logs | `%LOCALAPPDATA%\WAN\DownloadManager\logs\wdm.log` (secrets redacted); About & Diagnostics shows events |
