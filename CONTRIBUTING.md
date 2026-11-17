# Contributing

1. Read `docs/ARCHITECTURE.md` and `docs/SECURITY.md`.
2. Branch from `main`; keep changes focused; add tests (engine changes need a `MockHttpServer` test).
3. `mvn -B test` and `npm --prefix browser-extensions test` must pass.
4. Rules: no fake progress or qualities; all premium checks via `EntitlementService`; all outbound URLs via `UrlPolicy`;
   no secrets in code, SQLite or logs; FFmpeg only through `FfmpegService`; UI work off the FX thread.
5. Update `docs/` (status, tracker, known issues) with user-visible changes.
