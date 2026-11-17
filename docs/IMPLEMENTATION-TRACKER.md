# Implementation tracker (acceptance criteria, PART 24)

Legend: ✅ done and tested here · 🟡 implemented, not fully proven here · ⛔ blocked by an external dependency

| Criterion | State | Evidence |
|---|---|---|
| WAN Identity Hub authentication with actual APIs | 🟡 | Real hub E2E passed with the proposed patch (`HubEndToEndTest`); hub `main` lacks public clients (KNOWN-ISSUES 1) |
| WAN account profile displayed | ✅ | Account view; claims from ID token + userinfo |
| Subscription and entitlements integrated | 🟡 | `EntitlementService` from hub `wan_entitlement` roles; no billing catalog (⛔ 3) |
| Offline authorization tested | ✅ | `IdentityFlowTest` (cache, tamper, expiry) |
| WANBAHJAHKAS design principles | ✅ | `design/*.css`, WANBAHJAHKAS-INTEGRATION.md, screenshots |
| Desktop UI fully functional | 🟡 | 16 screens wired to real services; screenshot tour; no TestFX |
| Spring Boot backend runs locally | ✅ | `ApplicationContextTest`, E2E |
| SQLite persistence | ✅ | `RepositoryRoundTripTest`, API tests |
| Direct HTTP downloads | ✅ | `DownloadJobTest`, `DownloadApiTest` |
| Pause and resume | ✅ | engine + API tests |
| Multi-connection when supported | ✅ | 8-part SHA-256 integrity test; fallback without ranges |
| Download recovery | ✅ | re-queue on start + parts/validators (`etagMismatchOnResume…`) |
| Queue management | ✅ | `stoppedQueueHoldsDownloadsUntilStarted` |
| Batch download | ✅ | `batchNeedsTheBatchFeatureAndDeduplicates` |
| Video detection for supported sources | ✅ | `MediaTest`, browser E2E |
| Quality selection reflects real sources | ✅ | HLS variants from manifest only; protected refused |
| Chrome integration | ✅ | browser E2E (Chromium) |
| Edge integration | 🟡 | same build as Chrome; not run in Edge |
| Firefox integration | 🟡 | built; not run in Firefox |
| Native Messaging | ✅ | `NativeHostTest`, browser E2E |
| FFmpeg conversion | ✅ | `MediaTest` conversion + cancel + invalid input |
| Scheduler | 🟡 | window logic unit-tested; tick wiring not integration-tested |
| Download history | ✅ | API tests, CSV export |
| Settings persist | ✅ | settings API test |
| System tray | 🟡 | implemented; not run on Windows |
| Windows installer builds | ⛔/🟡 | script written; needs Windows + WiX (KNOWN-ISSUES 8) |
| Security tests pass | ✅ | `SecurityUnitTest`, API auth tests, contract tests |
| Automated tests pass | ✅ | 55 + 4 + 3 pass (1 opt-in skipped) |
| Documentation complete | ✅ | docs/ |
| Repository organized | ✅ | PART 20 layout |

## Spec items not implemented
- `media-engine/`, `database/schemas` top-level folders: media code lives in `backend/.../media`, schema in Flyway migrations (no duplication).
- Separate `WanButton.java`… classes: one `Wan` factory class provides all listed components (documented in WAN-DESIGN-SYSTEM-AUDIT.md).
- First-launch wizard, separate email-verification/MFA hand-off screens: verification and MFA happen on the hub's own pages during sign-in; no separate screens were needed.
