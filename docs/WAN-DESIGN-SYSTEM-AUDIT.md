# WAN Design System Audit (WANBAHJAHKAS + WAN Identity Hub)

Audited: `saddam22/wanbahjahkas` @ `e30bf6e` and `saddam22/wan-identity-hub` @ `1a5d221` (2026-11-16).

## Sources found

| Source | What it contains |
|---|---|
| `wanbahjahkas/apps/web/src/app/globals.css` | Corporate tokens (Tailwind 4 `@theme`): navy 950–700, brand blue 50–700, gold accent, surface/ink/muted, card/lift shadows, `.btn`, `.btn-primary`, `.card`, focus ring, reduced-motion rule |
| `wanbahjahkas/apps/web/src/lib/theme.ts` | Admin-managed brand colours (strict `#RRGGBB` validation, primary + accent override) |
| `wanbahjahkas/apps/web/src/components/site/Logo.tsx` | Corporate "W" roundel monogram (blue gradient `#3b82f6 → #1e3a8a`) |
| `wan-identity-hub/src/styles.css` | Full light **and dark** token set used by every WAN ID screen: navy shell, electric blue `#1d5bf5`, semantic success/warning/danger/violet/info with background tints, radii 8/10/14/20, shadow scale, 264 px sidebar |
| `wan-identity-hub/src/layout/AppShell.tsx`, `AuthShell.tsx` | Sidebar + header shell, split auth layout (hero panel + card) |

## Tokens adopted (shared)

| Token | Light | Dark | Origin |
|---|---|---|---|
| navy-950 / 900 / 800 | `#050d2b` / `#0a1640` / `#0f1f57` | same | WANBAHJAHKAS |
| primary (Electric Blue) | `#1d5bf5` | `#3b7bff` (lifted for contrast) | Identity Hub |
| primary-600 | `#1647d4` | `#1d5bf5` | Identity Hub |
| brand-500/600 | `#2563eb` / `#1d4ed8` | — | WANBAHJAHKAS |
| secondary (Cyan) | `#0891b2` | `#22d3ee` | new, per product brief |
| accent (Purple) | `#6d28d9` | `#a78bfa` | Identity Hub `--violet` |
| gold highlight | `#fbbf24` | `#fbbf24` | WANBAHJAHKAS |
| bg / surface / surface-2 | `#f3f6fb` / `#ffffff` / `#f7f9fc` | `#0b1222` / `#121b2f` / `#17223a` | Identity Hub |
| border / border-strong | `#e2e8f1` / `#c9d3e2` | `#23314d` / `#31415f` | Identity Hub |
| text / text-2 / muted | `#0e1a33` / `#33415c` / `#64748b` | `#e7edf8` / `#c3cde0` / `#8d9bb5` | Identity Hub |
| success / warning / danger | `#15803d` / `#b45309` / `#c62828` | `#4ade80` / `#fbbf24` / `#f87171` | Identity Hub |
| radius sm / md / lg / xl | 8 / 10 / 14 / 20 px | | Identity Hub |
| font | Inter / Segoe UI; Hind Siliguri / Noto Sans Bengali for Bengali | | both |

Implemented in `desktop/src/main/resources/design/*.css` (JavaFX looked-up colours, since JavaFX CSS
has no `var()`): `colors.css` defines `-wan-*` for the light theme, `dark-theme.css` redefines them.

## Component mapping

| Web component | JavaFX | Status |
|---|---|---|
| `.btn`, `.btn-primary`, ghost | `WanButton` (variants PRIMARY, SECONDARY, GHOST, DANGER) | adapted |
| `.card` | `WanCard` | adapted |
| AppShell sidebar (264 px, navy) | `WanSidebar` | adapted |
| AppShell header | `WanHeader` | adapted |
| Field + input | `WanTextField` | adapted |
| Progress ring / bar | `WanProgressBar` | adapted |
| Toasts (`state/app.tsx`) | `WanNotification` | adapted |
| Modal | `WanDialog` | adapted |
| Toggle | `WanToggle` | adapted |
| Empty state | `WanEmptyState` | adapted |
| Logo roundel | `WanLogo` (vector, own "W↓" mark — product-specific, same roundel language) | adapted |
| Background videos, sliders, newsletter, funding forms | — | web-only, not reused |
| Admin ResourceForm | — | web-only (Laravel admin) |

## Business logic reuse

| Existing module | Reuse decision |
|---|---|
| WAN Identity Hub OIDC | **Reused** as the only identity provider (no local passwords) |
| WANBAHJAHKAS OIDC relying party (`lib/auth/oidc.ts`) | Pattern reused: discovery, PKCE S256, state + nonce, strict return paths, HTTP issuer only for localhost in dev |
| WANBAHJAHKAS theme validation (`theme.ts`, strict `#RRGGBB`) | Pattern reused for any user/branding colour input |
| WANBAHJAHKAS payments (SSLCommerz, manual TrxID) | **Not duplicated.** Server-side Laravel module; a desktop app must not hold merchant credentials. Upgrade links to a configured WAN billing URL |
| Comms / notifications service | Not required; local notifications only (optional future WAN Notification Service) |
| Bilingual (en/bn) i18n | Desktop ships English + Bengali resource bundles |

## Must not be duplicated
Password storage, registration, MFA, account recovery (all hub-hosted), payment processing, corporate CMS.
