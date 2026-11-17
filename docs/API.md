# Local API (`/api/v1`)

Listens on `127.0.0.1:<random port>`; port and per-run token in `runtime.json` (owner-only). Every
request needs `Authorization: Bearer <token>`; any `Origin` header is refused (403); `Host` must be
loopback. Errors: `{"error":{"code","message","field?"}}`. Only `/actuator/health` is open.
These are **local application routes**, not WAN Identity Hub endpoints.

## Downloads
| Method | Path | Notes |
|---|---|---|
| GET | `/downloads?filter=all\|active\|completed\|failed` | `DownloadView[]` |
| POST | `/downloads` | `{url, fileName?, saveDir?, connections?, queueId?, startNow?, pageUrl?, headers?, allowDuplicate?, source?}` → 201; 409 `duplicate`; 402 `entitlement_required` |
| GET | `/downloads/{id}` | |
| POST | `/downloads/{id}/pause` · `/resume` · `/retry` · `/cancel` | cancel deletes the partial file |
| DELETE | `/downloads/{id}?deleteFile=false` | 204 |
| POST | `/downloads/pause-all` · `/resume-all` · `/retry-failed` | |
| POST | `/downloads/batch` | `{text, queueId?, saveDir?, preview?}` → `{added, duplicates, invalid, lines[]}` |

## Queues and schedules
`GET/POST /queues`, `PUT /queues/{id}`, `POST /queues/{id}/start`, `POST /queues/{id}/pause`,
`DELETE /queues/{id}`, `POST /queues/{id}/items {downloadId, move?}`;
`GET/POST /schedules`, `PUT/DELETE /schedules/{id}` (`{name, queueId, startTime, endTime?, daysOfWeek[], oneShotDate?, speedLimitBps?, enabled?}`).

## Media
`GET /media/sources`, `POST /media/detect` (Report), `GET /media/sources/{id}`, `DELETE /media/sources`,
`POST /media/sources/{id}/download {formatId}`, `POST /media/convert {inputPath, format, outputDir?}`,
`GET /media/conversions`, `POST /media/conversions/{id}/cancel`, `GET /media/ffmpeg`.

Report: `{pageUrl, title?, browser?, sources:[{url, mimeType?, width?, height?, durationSec?, poster?}]}` (≤50).

## Browser (used by the native host)
`GET /browser/status`, `POST /browser/connect {browser, extensionVersion}` → extension config,
`POST /browser/media` (Report), `POST /browser/download {url, fileName?, pageUrl?, mimeType?, browser}`.

## Account (local view of the WAN session)
`GET /account`, `GET /account/profile`, `GET /account/subscription` (`prices: null` — no catalog exists),
`GET /account/entitlements`, `POST /account/login?signup=false` → `{authorizationUrl, redirectUri}`,
`POST /account/login/cancel`, `POST /account/refresh`, `POST /account/logout` → `{revoked, endSessionUrl}`.

## Settings, history, stats
`GET/PUT /settings`, `POST /settings/reset`, `GET /history?q&status&limit`, `GET /history/export` (CSV,
formula-injection safe), `DELETE /history`, `GET /stats`, `GET /events`.

## WebSocket (`/ws/<topic>?access_token=<token>`)
`downloads` (DownloadView / `{deleted}`), `download-speed` (`{bps, active, at}` each second),
`media-detection` (SourceView), `notifications` (`{level,title,message,subjectId,at}` / account changes),
`conversion` (ConversionJob).
