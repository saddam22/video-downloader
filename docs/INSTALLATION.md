# Installation

## End users (Windows 10/11 x64)
1. Run `WAN Download Manager-<version>.msi` (per-user, no admin rights). It installs the app, a private
   Java runtime and the native messaging host, and adds Start-menu and desktop shortcuts.
2. Install the WAN Download Manager extension for Chrome, Edge or Firefox (store links once published;
   until then load `browser-extensions/dist/<browser>` unpacked — see DEVELOPMENT.md).
3. Optional: install FFmpeg (e.g. `winget install Gyan.FFmpeg`) for conversion, HLS remuxing and audio extraction,
   or point Settings → Media to `ffmpeg.exe`/`ffprobe.exe`.
4. Optional: Account → Continue to WAN Login (needs a configured WAN Identity Hub).

Data: `%LOCALAPPDATA%\WAN\DownloadManager` (database, logs, vault). Downloads default to
`%USERPROFILE%\Downloads\WAN\<Category>`. Uninstall via Apps & features; data folder is kept (delete
manually to remove everything).

## Configuration for an organisation build
Pass to `scripts/package.ps1`: `-Issuer https://id.example.org/oidc -ClientId wan-download-manager`
(only after the hub patch is deployed — see WAN-IDENTITY-INTEGRATION.md). Optional JVM properties:
`wan.identity.hub-web-url`, `wan.billing.url`, `wan.licensing.offline-grace-days`,
`wan.security.allow-private-network-downloads`.
