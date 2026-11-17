# Database

SQLite file `wdm.db` in the data folder (`%LOCALAPPDATA%\WAN\DownloadManager`). Schema owned by Flyway
(`backend/src/main/resources/db/migration`). Ids are UUID text, times are epoch milliseconds,
foreign keys on, WAL journal, one pooled connection. **No passwords or tokens are stored** — refresh
tokens live in the OS vault (DPAPI on Windows).

```
download_categories 1─┐
                      │ category_id
download_queues 1─┐   ▼
                  │  downloads 1──* download_parts      (byte ranges: start, end, downloaded, status)
                  │     │  1──* download_headers         (Referer / Accept-Language only; x-wdm-* internal)
                  └──* queue_items *──1 (unique per download, position = order)
schedules *──1 download_queues
media_sources 1──* media_formats                       (verified formats only)
download_history (independent of downloads; survives deletion)
browser_sessions · speed_statistics (per minute) · conversion_jobs · app_settings (JSON document)
account_cache · entitlement_cache (HMAC-signed) · device_registration_cache · application_events (redacted)
```

| Table | Purpose / notes |
|---|---|
| `downloads` | Recovery source of truth: URL, final URL, validators (ETag, Last-Modified), total/downloaded bytes, status (`QUEUED·DOWNLOADING·PAUSED·COMPLETED·FAILED·CANCELLED`), connections, temp path, SHA-256, `name_locked` |
| `download_parts` | One row per range; offsets persisted every ≤2 s and on pause/failure; deleted on completion |
| `download_headers` | Non-sensitive request headers; `Cookie`/`Authorization` refused by the service |
| `download_categories` | Seeded: Video, Music, Compressed, Documents, Programs, APKs, Images, Disk Images, Other |
| `download_queues`, `queue_items` | Queue state persists (recovery); `queue-main` built in |
| `media_sources`, `media_formats` | Fingerprint = SHA-256 of normalised source URL (duplicate filter) |
| `schedules` | HH:mm window, days, optional one-shot date and speed cap |
| `app_settings` | Key `settings` = JSON of `Settings` record (unknown keys dropped, missing → defaults) |
| `speed_statistics` | Real bytes per minute and peak bps (dashboard) |
| `conversion_jobs` | FFmpeg jobs with progress and error text |
| `account_cache` | Display claims only |
| `entitlement_cache` | Tier, status, roles, verified/expiry, HMAC signature (key in vault) |
| `device_registration_cache` | Local installation id; `registered_with` NULL (hub has no device API) |
| `application_events` | INFO/WARN/ERROR/AUDIT, messages passed through `Redactor` |

Crash recovery: on start, `DOWNLOADING` rows become `QUEUED` and resume from their parts (validated
against ETag/Last-Modified/length; any change restarts from zero).
Backup: copy the data folder while the app is closed. Versioning: Flyway `flyway_schema_history`.
