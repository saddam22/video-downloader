# WANBAHJAHKAS integration

WANBAHJAHKAS LTD (`saddam22/wanbahjahkas`, Next.js + Laravel) is the design and pattern reference.
WAN Download Manager has **no runtime dependency** on it.

| Pattern / asset | WANBAHJAHKAS source | Use in WAN Download Manager |
|---|---|---|
| Navy + royal-blue palette, gold accent | `apps/web/src/app/globals.css` (`--color-navy-*`, `--color-brand-*`, `--color-gold-400`) | `desktop/.../design/colors.css` (`-wan-navy-*`, `-wan-gold`), sidebar gradient |
| Card style (radius 1 rem, soft shadow) | `.card`, `--shadow-card` | `.wan-card` (radius 14, dropshadow) |
| Primary button with blue glow | `.btn-primary` | `.wan-button.primary` |
| Visible focus ring (3 px brand) | `:focus-visible` | `-wan-focus` border on focused controls |
| Reduced motion respect | `prefers-reduced-motion` | Only short fades on toasts; no decorative animation |
| Roundel logo | `components/site/Logo.tsx` | `MainShell.logo()` — same roundel language, own download-arrow mark |
| Strict colour validation for admin themes | `lib/theme.ts` | Not needed yet (no user colours); pattern noted for future branding settings |
| OIDC relying party (PKCE, state, nonce, http only on localhost) | `lib/auth/oidc.ts` | Same rules in `identity/OidcClient` (public client variant) |
| Hub changes shipped as patches in `docs/integrations/` | `docs/integrations/*.patch` | `docs/integrations/wan-identity-hub-desktop-client.patch` |
| Error body `{error:{code,message,field}}` | Identity Hub + Laravel API | `GlobalExceptionHandler` |
| Bilingual en/bn | `i18n/en.json`, `bn.json` | Language setting exists; Bengali strings not yet translated (KNOWN-ISSUES) |
| Payments (SSLCommerz: bKash, Nagad, Rocket, cards; manual TrxID) | `apps/api` payment module | **Not reused**: server-side merchant credentials cannot live in a desktop app. Upgrade opens `wan.billing.url` once a WAN billing service exists |

Web-only components not ported: background videos, sliders, newsletter, funding/invest forms, admin
resource forms, comms widgets.
