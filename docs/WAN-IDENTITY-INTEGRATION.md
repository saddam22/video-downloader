# WAN Identity Hub Integration

WAN Download Manager has **no local accounts and no passwords**. Sign-in, registration, email/phone
verification, MFA and account recovery all happen on WAN Identity Hub, in the user's system browser.
Audit of the hub: [`WAN-IDENTITY-AUDIT.md`](WAN-IDENTITY-AUDIT.md).

## Status

| Item | State | Evidence |
|---|---|---|
| Desktop client code (PKCE, loopback, ID-token validation, refresh, revocation, offline cache) | Implemented | `backend/src/main/java/com/wan/downloadmanager/identity/*` |
| Negative paths (state, nonce, expired token, rejected refresh, tampered/expired offline cache) | Tested in CI | `IdentityFlowTest` (10 tests, mock provider with the hub's contract) |
| Against the **real hub** | Tested locally 2026-11-16 | `HubEndToEndTest` (opt-in) passed against `wan-identity-hub@1a5d221` + the patch below |
| Hub change for public desktop clients | **Proposed, not merged** | [`integrations/wan-identity-hub-desktop-client.patch`](integrations/wan-identity-hub-desktop-client.patch); hub suite 85/85 and 19/19 UI unit tests pass with it |
| Production client registration | **Not done** — needs the hub operator | see "Going live" |

## Flow (RFC 8252 native app + OIDC)

```
WAN Download Manager                    System browser                  WAN Identity Hub (/oidc)
 1. discover  ─────────────────────────────────────────────────────────▶ /.well-known/openid-configuration
 2. listen on http://127.0.0.1:<random>/callback  (one request only)
 3. open ──▶ /auth?client_id=wan-download-manager&code_challenge=S256(..)&state&nonce&prompt=consent
                                       ──▶ hub login / registration / MFA (hub UI)
 4. ◀── 127.0.0.1:<port>/callback?code&state&iss
 5. verify state + iss; POST /token (code, code_verifier, client_id — no secret)
 6. validate ID token: RS256 via JWKS, iss, aud, azp, exp/iat ±60 s, nonce
 7. GET userinfo (bearer) — subject must equal the ID token's
 8. tier ← wan_entitlement {platform: wan-download-manager, status, roles}
 9. refresh token → OS vault (DPAPI on Windows); claims → account_cache; signed tier → entitlement_cache
```

Re-verification: every 30 minutes and at start-up a `refresh_token` grant (rotating) re-reads the
entitlement. Logout: RFC 7009 revocation of the refresh token, local data cleared; the hub's
`end_session_endpoint` URL (with `id_token_hint`) is returned so the UI can also end the hub's web session.

## Entitlements and offline use

- Tier = hub entitlement roles on platform `wan-download-manager`: `business` > `pro` > `free`;
  status other than `active`, unknown roles, signed-out or unconfigured → **Free**.
- `EntitlementService` is the only place that answers "may the user do X?" (`catalog.json`).
- Offline: the last verified tier is stored with an HMAC-SHA256 signature whose key lives in the OS
  vault. It is honoured until `verified_at + wan.licensing.offline-grace-days` (default 7; the hub has
  no licensing policy yet, so this is a local configuration value — documented as a known gap).
  A tampered row or an expired grace period → Free.
- On non-Windows platforms the vault is in-memory, so the offline cache cannot be trusted after a
  restart → Free until the next online verification (safe failure).

## Configuration (`application.yml` / environment)

| Property | Default | Meaning |
|---|---|---|
| `wan.identity.issuer` | empty → sign-in disabled | e.g. `https://id.example.org/oidc` (http only for localhost) |
| `wan.identity.client-id` | empty | `wan-download-manager` once registered |
| `wan.identity.scopes` | `openid profile email offline_access wan_entitlements` | filtered to the issuer's `scopes_supported` |
| `wan.identity.hub-web-url` | empty | hub UI base for "Manage account" / "Create account" links |
| `wan.licensing.offline-grace-days` | 7 | offline grace period |
| `wan.billing.url` | empty | upgrade page; the upgrade button is disabled while empty |

Environment variables work too (`WAN_IDENTITY_ISSUER`, `WAN_IDENTITY_CLIENT_ID`, …).

## Going live (what the hub operator must do)

1. Apply `docs/integrations/wan-identity-hub-desktop-client.patch` to `saddam22/wan-identity-hub`
   (`git am`), run its tests, deploy.
2. In production set `WDM_ENABLED=true` on the hub (the client is on by default only in development).
   The client is public: there is **no secret** to distribute.
3. Decide tiers: set `roles` of the `wan-download-manager` entitlement to `pro` / `business` through the
   Platform API (`PUT /platform-api/v1/entitlements/{sub}` currently only sets status — role
   assignment is done by the hub admin; a billing service would automate this later).
4. Ship the installer with `WAN_IDENTITY_ISSUER` and `WAN_IDENTITY_CLIENT_ID` set.

## Running the end-to-end test yourself

```bash
cd wan-identity-hub && git am ../wan-download-manager/docs/integrations/wan-identity-hub-desktop-client.patch
cd server && npm ci && DATABASE_URL=postgresql://postgres:postgres@localhost:5432/wan_identity npm run migrate -- --demo
DATABASE_URL=postgresql://postgres:postgres@localhost:5432/wan_identity WEB_DIR= npm run start
# in this repository
WAN_HUB_ISSUER=http://localhost:4100/oidc WAN_HUB_PSQL=postgresql://postgres:postgres@localhost:5432/wan_identity \
  mvn -f backend/pom.xml test -Dtest=HubEndToEndTest
```
