# WAN Identity Hub — Integration Audit

Audited repository: `saddam22/wan-identity-hub`, commit `1a5d221` (shallow clone, 2026-11-16).
Everything below was read from source (`server/src/**`, `server/migrations/**`, `src/api/**`,
`README.md`). Nothing is assumed.

## 1. Existing architecture

| Part | Technology | Path |
|---|---|---|
| Web UI (login, registration, profile, security, platforms, admin) | React 19 + TypeScript, custom bundler | `src/` → `dist/index.html` |
| Backend API | Node 22, Fastify 5.12, PostgreSQL 14+ | `server/src/app.ts`, `server/src/routes/*` |
| OpenID Provider | `oidc-provider` 9.13.0 (certified) mounted at `/oidc` | `server/src/oidc/*` |
| Platform API (server-to-server) | Fastify, HTTP Basic with OIDC client credentials | `server/src/routes/platformApi.ts` at `/platform-api/v1` |

Issuer: `${PUBLIC_URL}/oidc` (e.g. `http://localhost:4100/oidc` in development). Discovery at
`${issuer}/.well-known/openid-configuration`, keys from discovery's `jwks_uri`.

## 2. Existing authentication flow (as implemented)

1. A relying party redirects to `${issuer}/auth` with `response_type=code`, `scope=openid …`,
   `code_challenge` (**S256, required for every client** — `pkce.required: () => true`), `state`, `nonce`.
2. `GET /oidc/interaction/:uid` (`server/src/oidc/routes.ts`):
   - no hub session (`wan_sid` cookie) → redirect to the hub's own `#/login` (or `#/register` when
     `screen_hint=signup`) with `?return=…&platform=…`, then back to the interaction;
   - hub session present → login finished; consent is recorded automatically (first-party platforms);
   - an entitlement with status `suspended` or `rejected` for the client's `platformId` → `access_denied`.
   - On first consent the hub inserts an `entitlements` row (`status='active'`, first requestable role or `member`).
3. Token endpoint: **`client_secret_basic` for every client** (`token_endpoint_auth_method` is hard-coded).
4. `grant_types: ["authorization_code"]` only — **no refresh tokens** are issued.
5. TTLs: authorization code 60 s, access token 600 s, ID token 600 s, OIDC session/grant 14 days.
6. Access tokens are opaque, except for clients with a `resource` (RFC 8707), which get RS256 JWTs.
7. RP-initiated logout (`end_session_endpoint`) and back-channel logout are enabled.
8. Hub sign-out or password change ends the "WAN login" (custom `hub_session` interaction check).

Login methods inside the hub UI: email or mobile + password, TOTP 2FA (`/auth/mfa/verify`),
passkeys (WebAuthn, `server/src/routes/passkeys.ts`). Google/Apple start endpoints exist but refuse
("not configured"). Registration (`/registrations/*`) verifies email or phone with a one-time code first.

## 3. Available APIs relevant to a desktop client

| Endpoint | Auth | Usable by WAN Download Manager? |
|---|---|---|
| OIDC discovery, `/auth`, `/token`, `/jwks`, `/session/end` | OIDC | **Yes**, once a public client exists (see gap G1) |
| OIDC `userinfo_endpoint` (oidc-provider default, enabled) | Bearer access token | **Yes** — `sub`, `name`, `preferred_username`, `locale`, `birthdate`, `email`, `email_verified`, `phone_number`, `phone_number_verified` per scope |
| `/api/me`, `/api/me/entitlements`, `/api/me/security`, … | **Cookie session + CSRF only** | No — browser-only; no bearer authentication |
| `/platform-api/v1/entitlements/{sub}` | Client secret (Basic) | **No** — a desktop installer must never carry a secret |
| Hub UI pages `#/register`, `#/forgot-password`, `#/security`, `#/verification`, `#/platforms` | Browser | Yes — opened in the system browser (registration, recovery, MFA, profile edit) |

Scopes / claims (`provider.ts`): `openid → sub`; `profile → name, preferred_username, locale,
birthdate, updated_at`; `email → email, email_verified`; `phone → phone_number, phone_number_verified`.
`email_verified` / `phone_number_verified` are true only for verified contacts.

## 4. Client registration requirements

Clients are configured in `server/src/config.ts`: either built-in per-platform env vars
(`AGG_*`, `BP_*`, `WBJ_*`, …) or the `OIDC_CLIENTS` JSON (which **replaces** the built-ins).
Each entry: `client_id`, `client_secret` (32+ chars, mandatory), `redirect_uris`,
`post_logout_redirect_uris`, `platformId`, optional `resource`, `backchannel_logout_uri`.
The UI's platform list is `src/api/catalog.ts` (`PLATFORMS`, `REQUESTABLE_ROLES`).

There is **no `wan-download-manager` client and no `wan-download-manager` platform** in the hub today.

## 5. Token format and validation strategy

- ID token: RS256 JWT signed with keys from `OIDC_JWKS` (prod) or a generated key (dev); claims
  carried in the ID token (`conformIdTokenClaims: false`).
- Desktop validation plan: verify signature against discovery `jwks_uri`, `iss` = discovered issuer,
  `aud` contains our `client_id`, `azp` if present, `exp`/`iat` with 60 s skew, `nonce` = the one we sent.
- Access token: opaque → used only as a bearer for `userinfo`; never parsed.
- Tokens are kept in the OS credential store (Windows Credential Manager), never in SQLite or logs.

## 6. Subscription and entitlement capabilities

- **No subscription, plan, billing, licence or payment model exists in the hub.** (grep of
  `server/src` for subscription/billing/payment/plan/license returns nothing.)
- Entitlements table: `(user_id, platform_id, status ∈ active|pending|rejected|suspended, roles[], permissions[], granted_at)`.
  Roles/permissions are opaque to the hub and decided by each platform.
- A platform can suspend/restore a user (`PUT /platform-api/v1/entitlements/{sub}`) — suspended users
  are refused at WAN login.
- **Devices:** `GET /api/me/security` returns `devices: []` (placeholder) and `DELETE /me/devices/:id`
  is a no-op returning the overview. There is **no device registration API**.
- Payments live elsewhere: WANBAHJAHKAS (`apps/api`, Laravel) implements SSLCommerz (bKash, Nagad,
  Rocket, Upay, cards) + manual mobile banking with staff-verified TrxID. That is a corporate-site
  module, not a WAN-wide billing service.

## 7. Missing functionality (gaps)

| # | Gap | Impact on WAN Download Manager | Resolution |
|---|---|---|---|
| G1 | No **public client** support (all clients `client_secret_basic`) | A desktop app cannot sign in without embedding a secret (forbidden) | Hub extension: native client with `token_endpoint_auth_method: "none"`, `application_type: "native"`, loopback redirect `http://127.0.0.1/callback` (RFC 8252 §7.3, port-agnostic in oidc-provider) — patch provided |
| G2 | No **refresh tokens** | Users would have to re-login every 10 min | Same patch: `refresh_token` grant + `offline_access` scope for the native client only, rotation on use |
| G3 | No **token revocation** endpoint | Logout cannot revoke | Same patch enables `features.revocation`; until deployed the client falls back to `end_session` + local deletion |
| G4 | No **bearer-readable entitlement** for the signed-in user | Desktop cannot read its own tier | Same patch adds scope `wan_entitlements` → claim `wan_entitlement` `{platform, status, roles}` for the client's own platform only |
| G5 | No **subscription/plan catalog** | Free/Pro/Business cannot be authoritative yet | Tier = hub entitlement roles on platform `wan-download-manager` (`free` default, `pro`, `business`) set by the platform owner through the Platform API (server-side tooling, not the desktop). Catalog of prices: not invented — shown as "not available" until a WAN billing service exists |
| G6 | No **device registration** | Device limits can't be enforced | Local installation id only; device UI shows this device and states the hub has no device API |
| G7 | No WAN billing service | No purchase flow | Upgrade button opens the configured `wan.billing.url` only if set; otherwise disabled with explanation |

## 8. Integration requirements (what we built against)

- Configuration (no invented values — all empty by default; sign-in is disabled until set):
  `wan.identity.issuer`, `wan.identity.client-id` (proposed: `wan-download-manager`),
  `wan.identity.scopes` (`openid profile email offline_access wan_entitlements`).
- Flow: system browser → `issuer/auth` with PKCE S256 + state + nonce → loopback listener on
  `127.0.0.1:<ephemeral>` → code exchange (no secret) → ID token validation → userinfo → claims.
- If the issuer does not advertise `wan_entitlements` / `offline_access` / revocation in discovery,
  the client degrades: tier `free`, no silent refresh, local-only logout. Behaviour is covered by tests.
- Proposed hub change: [`docs/integrations/wan-identity-hub-desktop-client.patch`](integrations/wan-identity-hub-desktop-client.patch),
  documented in [`WAN-IDENTITY-INTEGRATION.md`](WAN-IDENTITY-INTEGRATION.md). Not pushed to the hub
  repository (outside this project's branch scope); it follows the precedent of WANBAHJAHKAS, which
  ships its hub patches in `docs/integrations/` the same way.
