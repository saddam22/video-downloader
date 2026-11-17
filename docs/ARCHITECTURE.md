# Architecture

Modular monolith, local-first. One desktop process hosts the JavaFX UI and an embedded Spring Boot
backend bound to `127.0.0.1`. Browser extensions reach it only through the native messaging host.

```
┌──────────────────────── WAN Download Manager (one process, per user) ────────────────────────┐
│ JavaFX desktop (desktop/)                     Embedded Spring Boot backend (backend/)          │
│  MainShell · 16 views · Wan components  ──►   services in-process (no HTTP hop for the UI)     │
│  Tray · single-instance lock · themes         REST /api/v1 + WebSocket /ws/* on 127.0.0.1:rand │
│                                               LocalApiFilter: bearer token, Host + Origin check │
│   download/  engine/  queue/  scheduler/  history/  media/  browser/  identity/  entitlement/   │
│   storage/ (settings)  notification/ (EventBus, EventLog)  security/ (UrlPolicy, SafeFiles)     │
│                    SQLite (wdm.db, Flyway)   ·   OS vault (DPAPI)   ·   files on disk           │
└────────────────────────────────────────────▲───────────────────────────────────────────────────┘
         runtime.json (port + token, owner-only) │ HTTP 127.0.0.1
┌──────────────────────────┐ stdio  ┌──────────┴─────────┐
│ Extension (Chrome/Edge/  │◄──────►│ Native host (Java) │  strict message contract, allow-listed origins
│ Firefox, MV3, TypeScript)│        └────────────────────┘
└──────────────────────────┘
      WAN Identity Hub (OIDC, optional, internet)  ◄── identity/ (code + PKCE, loopback redirect)
```

## Integration map (WAN ecosystem)

| Need | Provided by | How | Status |
|---|---|---|---|
| Sign-in, registration, MFA, recovery | WAN Identity Hub | OIDC discovery + code/PKCE in system browser | Implemented; needs hub patch for public clients (proposed) |
| Profile | WAN Identity Hub | ID token + `userinfo` claims | Implemented |
| Plan / entitlements | WAN Identity Hub entitlements (platform `wan-download-manager`) | `wan_entitlement` claim (patch) | Implemented; roles set by hub admin |
| Prices, payments | none yet (WANBAHJAHKAS has SSLCommerz for its own site) | `wan.billing.url` link | Not available — no prices shown |
| Device management | none in hub | local installation id only | Gap documented |
| Design tokens | Identity Hub `styles.css`, WANBAHJAHKAS `globals.css` | ported to JavaFX CSS | Implemented |
| Notifications / cloud / updates | future WAN services | none | Out of scope, app works without |

## Key decisions

| Decision | Reason |
|---|---|
| UI calls backend services in-process | No serialization or token handling inside the trusted process; the HTTP API exists for the native host and tools |
| One pooled SQLite connection, WAL | SQLite has one writer; avoids `SQLITE_BUSY`; progress persisted at most every 2 s per download |
| Engine independent of Spring (`JobStore` callbacks) | Testable against a local mock HTTP server; persistence is a detail |
| Positional writes into one `.wdmpart` file per download | Parts never need merging; resume = continue each range |
| HLS segments fetched by our fetcher, FFmpeg only on local files | Every network request passes the SSRF policy; FFmpeg's protocol whitelist is `file` |
| Formats only from manifests/probes | "Never display unavailable qualities": the page's claims never create an option |
| Tier from hub claims or HMAC-signed cache only | Editing local data cannot grant features |
| Hub change proposed as a patch, not a parallel auth service | Requirement: no duplicate identity system |

Package layout (`com.wan.downloadmanager`): `config`, `controller`, `dto`, `entity`, `repository`,
`security`, `identity`, `entitlement`, `download`, `engine`, `media`, `browser`, `queue`, `scheduler`,
`history`, `stats`, `storage`, `notification`, `exception`. Desktop: `desktop`, `desktop.ui`, `desktop.views`.
Native host: `nativehost`. The spec's `subscription`/`mapper`/`util` packages are folded into
`entitlement` and the services (no separate mapping layer was needed).

## Dependency versions

Java 21 · Spring Boot 3.5.7 (Spring 6.2, Hibernate 6.6, Flyway 11.7) · sqlite-jdbc 3.49 ·
hibernate-community-dialects (SQLite) · Nimbus JOSE+JWT 10.0.2 · JNA 5.15 (DPAPI) · JavaFX 21.0.5 ·
TypeScript 5.6 · @types/chrome 0.0.287 · FFmpeg (system) · tests: JUnit 5, AssertJ, Mockito, Playwright 1.63 (E2E).
