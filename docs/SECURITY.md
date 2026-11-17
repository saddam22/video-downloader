# Security

| Threat | Control | Where / test |
|---|---|---|
| Web pages calling the local API (CSRF, DNS rebinding) | Bind 127.0.0.1; random 256-bit token per run; refuse any `Origin`; loopback `Host` only; no CORS | `LocalApiFilter`; `ApplicationContextTest` |
| Token theft from disk | `runtime.json` and data folder owner-only (POSIX) / per-user `%LOCALAPPDATA%` ACL; token changes every run | `LocalApiAuth` |
| SSRF via URLs from pages/extensions | http(s) only, no user-info, block loopback, link-local (169.254.169.254), wildcard, multicast; private LAN configurable; re-checked on every redirect hop and HLS segment; https→http redirect refused | `UrlPolicy`, `HttpFetcher`; `SecurityUnitTest` |
| Path traversal / reserved names | Sanitised names (Windows reserved, control chars, length), resolve-inside-root check | `SafeFiles`; `SecurityUnitTest` |
| Overwriting files | Final name always new (`name (1).ext`); writes go to `.wdmpart`, atomic move | `DownloadJobTest.neverOverwritesAnExistingFile` |
| Executing downloads | Never executed or opened automatically | — |
| Command injection via FFmpeg | `ProcessBuilder` argument lists, fixed prefix, `-protocol_whitelist file`, inputs are local files | `FfmpegService` |
| XXE in DASH manifests | DOCTYPE disallowed, external entities off | `ManifestParser`; `MediaTest.manifestParsing` |
| DRM / access-control bypass | Encrypted HLS (`EXT-X-KEY` ≠ NONE, session keys) and DASH `ContentProtection` refused; live streams refused; no cookies forwarded | `MediaTest.encryptedAndDrmStreamsAreNeverOffered` |
| Credential leakage via headers | Only Referer/Accept/Accept-Language accepted; Cookie/Authorization refused; internal `x-wdm-*` never sent | `DownloadApiTest.refusesCredentialsHeadersAndBadUrls` |
| Malicious native messages | 1 MiB limit, strict schema (no unknown fields, URL schemes, ranges, control chars), allow-listed extension ids, host never returns the token | `MessageContract`; `NativeHostTest` |
| OAuth attacks | Public client, PKCE S256, state (constant-time), nonce, issuer check (`iss` param + ID token), JWKS signature, aud/azp, exp ±60 s, system browser only, loopback receiver accepts one request | `OidcClient`, `IdentityService`; `IdentityFlowTest` |
| Secrets at rest | Refresh token + cache key in DPAPI vault; none in SQLite/logs; in-memory on non-Windows | `WindowsDpapiVault` |
| Forged premium entitlements | Tier only from validated claims or HMAC-signed cache with expiry; tampering → Free | `IdentityFlowTest.refreshRotatesAndOfflineUsesTheSignedCache` |
| Secrets in logs/events | `Redactor` strips token-like query params, bearer values, JWTs; errors redacted | `SecurityUnitTest.secretsAreRedacted` |
| CSV formula injection in history export | Cells starting with `= + - @` prefixed with `'` | `DownloadApiTest.historyExportIsCsvWithFormulaProtection` |
| Extension privileges | Required: nativeMessaging, storage, contextMenus, activeTab, scripting, webRequest. Site access and downloads are **optional**, requested on user action; content in closed shadow DOM; no `innerHTML` with page data; CSP `script-src 'self'` | `browser-extensions/` |

Known gaps: dependency vulnerability scanning is not wired into CI (run `mvn org.owasp:dependency-check-maven:check`
and `npm audit` before release); installers are unsigned; DNS rebinding between the SSRF check and
connect is mitigated, not eliminated (see KNOWN-ISSUES).
