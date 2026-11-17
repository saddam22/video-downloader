# Release checklist

1. `scripts/test.ps1` green on Windows (FFmpeg on PATH); run `HubEndToEndTest` and `scripts/e2e-browser.mjs` against a staging hub.
2. Security: `mvn org.owasp:dependency-check-maven:check`, `npm audit --prefix browser-extensions`; review `docs/SECURITY.md`.
3. Bump versions: root `pom.xml`, `browser-extensions/*/manifest.json`, `package.json`, `CHANGELOG.md`.
4. Hub: desktop-client patch deployed, `WDM_ENABLED=true`, entitlement roles decided.
5. `scripts/package.ps1 -Version x.y.z -Issuer … -ClientId wan-download-manager` on Windows with WiX.
6. Sign MSI and both EXEs with the company certificate (not available yet).
7. Publish extensions (Chrome Web Store, Edge Add-ons, AMO); put the store ids into
   `native-messaging/host/src/main/resources/allowed-origins.properties` and the installer's registration, rebuild.
8. Smoke test: install over the previous version (upgrade UUID), check data kept, sign in, download, browser hand-off, uninstall.
