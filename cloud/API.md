# Cloud portal API (cloud/api ⇄ cloud/web)

REST surface the Next.js portal consumes. JSON everywhere; all money integer
cents; dates `YYYY-MM-DD` (venue-local business days); timestamps are ISO-8601
instants carrying the venue's offset (`2026-07-11T18:02:11.000-04:00`) — slice the
leading wall clock for display, `Date.parse` for arithmetic. In production Caddy serves the
portal at `/` and proxies `/v1/*` + `/health` to the API container, so the
browser talks same-origin (no CORS). In dev, Next rewrites `/v1/*` →
`http://localhost:8081`.

Errors: non-2xx bodies are `{ "error": "human message", "code": "machine_code" }`.
401 `not_authenticated` / `session_idle` / `session_expired`, 403 `totp_pending` (session exists but TOTP step not
done), 404/409/400 as usual.

## Auth

Session = opaque token in an `HttpOnly; SameSite=Lax; Path=/` cookie
`pos_portal_session` (Secure flag when the request came via https). Idle
timeout `PORTAL_SESSION_IDLE_MINUTES` (default 60): each authenticated request
refreshes last-used, except ones sent with `X-Background: 1` (portal
auto-refresh polls), which are served but don't count as activity. Absolute cap
`PORTAL_SESSION_MAX_HOURS` (default 12) from sign-in; the cookie's Max-Age is the
same cap. A dead session → 401 `session_idle` (plus `X-Session-Idle-Minutes`)
or `session_expired`, and the cookie is cleared; dead rows are swept at each
sign-in and every 15 min. Passwords BCrypt. TOTP: RFC 6238, SHA-1, 6 digits, 30 s
period, ±1 step tolerance. TOTP is REQUIRED: a user without it enrolled is
forced through setup at login.

- `POST /v1/auth/login` `{ "email", "password" }`
  - wrong creds → 401 `bad_credentials` (rate-limited: 10/min per IP+email → 429)
  - TOTP enrolled → `{ "stage": "totp", "pendingToken": "…" }` (token valid 5 min)
  - TOTP not yet enrolled → `{ "stage": "totp_setup", "pendingToken": "…",
      "secret": "BASE32…", "otpauthUri": "otpauth://totp/CopperLantern%20Pub:owner@…?secret=…&issuer=CopperLantern%20Pub" }`
- `POST /v1/auth/totp` `{ "pendingToken", "code" }` — verify at login. `code` is
  either the 6-digit authenticator code OR a one-time backup code (see below).
  Success → sets session cookie, `{ "ok": true }`. Bad code → 401 `bad_totp`.
- `POST /v1/auth/totp/confirm` `{ "pendingToken", "code" }` — first-login
  enrollment: verifies the code against the pending secret, enables TOTP, sets
  session cookie, and returns the recovery codes **once**:
  `{ "ok": true, "backupCodes": ["abcde-fghjk", …] }` (10 codes). Only their
  SHA-256 is stored; each is single-use. Re-enrollment issues a fresh set and
  invalidates the old ones.

Lockout recovery (single owner): backup codes cover a lost phone. If they're gone
too, the operator sets `RESET_TOTP_EMAIL=<owner-email>` and restarts — that user's
TOTP is wiped (re-enroll at next login) and old backup codes deleted. Clear the
env var afterwards, or every restart re-triggers the reset.
- `POST /v1/auth/logout` → clears cookie, revokes session.
- `GET /v1/auth/me` → `{ "email", "displayName", "venueName" }`.

## Reports (session-authed, tenant-scoped)

All take `?from=YYYY-MM-DD&to=YYYY-MM-DD` (inclusive, venue-local; default
today/today). A check belongs to the day of its `closedAt`.

**Store scope (every report).** `?venue=<id>` = that store only; no `venue` =
all of the tenant's stores combined, each over its own business days. A venue
id outside the session's tenant is a 404. Every report also carries a per-store
split, `byVenue` (one entry per in-scope store, so a single row when a store is
picked; rows from a specific store carry `venueId`):

| report | `byVenue` entry |
|---|---|
| summary, tax | `{ venueId, venueName, grossCents, netCents, taxCents, checkCount, avgCheckCents, voidCount, refundAmountCents }`; each `byDay` / `rows` day also has `byVenue: [{ venueId, grossCents, netCents, taxCents, checkCount }]` |
| by-venue | `venues: [ same as summary ]` |
| payments | `{ venueId, venueName, totalCents, rows: [payment row] }` |
| items, categories | top-level `{ venueId, venueName, grossCents, checkCount, qty }`; each row has `byVenue: [{ venueId, qty, revenueCents }]` (stores that sold it) |
| hourly | top-level as items; each hour has `byVenue: [{ venueId, grossCents, checkCount }]` (every store) |
| tables | top-level as items (closed-check gross / count) |
| exceptions | `{ venueId, venueName, voidCount, voidAmountCents, corkageCents }` |
| refunds | `{ venueId, venueName, count, grossCents, netCents, taxCents }` |
| cash-movements | `{ venueId, venueName, paidInCents, paidOutCents, netCents, inCount, outCount }` |
| shifts | `{ venueId, venueName, shiftCount, openCount, revenueCents, transactionCount, overShortCents }` |
| journal | `{ venueId, venueName, closedCount, voidCount, closedCents }` over the whole filtered range, not the page |

- `GET /v1/reports/summary`
  ```json
  { "grossCents": 0, "netCents": 0, "taxCents": 0,
    "checkCount": 0, "avgCheckCents": 0,
    "voidCount": 0, "voidAmountCents": 0,
    "corkageCents": 0, "serviceChargeCents": 0,
    "byDay": [ { "date": "2026-07-11", "grossCents": 0, "netCents": 0,
                 "taxCents": 0, "checkCount": 0 } ] }
  ```
- `GET /v1/reports/tax` — the tax-filing report.
  ```json
  { "rates": [ { "code": "GST", "labelFr": "TPS", "labelEn": "GST", "ratePercent": "5" },
               { "code": "QST", "labelFr": "TVQ", "labelEn": "QST", "ratePercent": "9.975" } ],
    "rows": [ { "date": "2026-07-11", "grossCents": 0, "netCents": 0,
                "taxCents": 0, "gstCents": 0, "qstCents": 0, "checkCount": 0 } ],
    "totals": { "grossCents": 0, "netCents": 0, "taxCents": 0,
                "gstCents": 0, "qstCents": 0, "checkCount": 0 } }
  ```
  (`taxCents` = Σ store-computed `taxIncludedCents`; `netCents` = gross − tax;
  `gstCents` / `qstCents` = Σ the per-sale GST / QST the stores charged, less
  what refunds reversed — 0 for sales synced without a breakdown, never
  estimated. `rates` lists the taxes the in-range sales carry, from the stores'
  own breakdown. Summary / by-venue rows and each day's `byVenue` entry carry
  `gstCents` / `qstCents` too.)
- `GET /v1/reports/payments` →
  `{ "rows": [ { "type": "CASH", "amountCents": 0, "count": 0 } ], "totalCents": 0 }`
  (type ∈ CASH | CARD | BANK_TRANSFER | STRIPE; amount = Σ amountApplied.)
- `GET /v1/reports/items` →
  `{ "rows": [ { "itemId", "nameFr", "nameEn", "categoryId", "categoryNameFr",
     "categoryNameEn", "qty", "revenueCents" } ] }` sorted by revenue desc.
  Off-menu open lines aggregate under itemId `null`, name "Open item".
- `GET /v1/reports/categories` →
  `{ "rows": [ { "categoryId", "nameFr", "nameEn", "qty", "revenueCents" } ] }`
- `GET /v1/reports/hourly` →
  `{ "rows": [ { "hour": 0, "grossCents": 0, "checkCount": 0 } ] }` (0–23, all 24 rows)
- `GET /v1/reports/tables` →
  ```json
  { "byZone": [ { "zoneId", "zoneNameFr", "zoneNameEn", "grossCents", "checkCount" } ],
    "byTable": [ { "zoneId", "zoneNameEn", "tableId", "tableLabel",
                   "grossCents", "checkCount" } ] }
  ```
- `GET /v1/reports/exceptions` — voids + corkage (discounts/comps don't exist
  at the POS yet; render zeros).
  ```json
  { "voids": [ { "checkId", "voidedAt", "tableLabel", "amountCents",
                 "reason", "voidedBy" } ],
    "voidCount": 0, "voidAmountCents": 0, "corkageCents": 0 }
  ```
- `GET /v1/reports/shifts` → `{ "rows": [ shift ] }`, newest first, where shift =
  ```json
  { "shiftId": 7, "status": "CLOSED", "openedAt": "…", "closedAt": "…",
    "openedBy": "1234", "closedBy": "1234",
    "openingFloatCents": 0, "revenueCents": 0, "transactionCount": 0,
    "avgCheckCents": 0, "corkageCents": 0,
    "tenderBreakdown": [ { "type", "amountCents", "count" } ],
    "expectedCashCents": 0, "closingCountCents": 0, "overShortCents": 0 }
  ```
  (open shift included with nulls for the close-only fields — that's the live
  X view; `GET /v1/reports/shifts/{id}` returns one.)
- `GET /v1/reports/journal?from&to&q&limit=50&offset=0` — searchable
  transaction journal over closed+voided checks. `q` matches check id, table
  label, or exact CAD amount.
  ```json
  { "total": 123,
    "rows": [ { "checkId", "status": "CLOSED|VOID", "closedAt", "tableLabel",
                "zoneNameEn", "grandTotalCents", "taxIncludedCents",
                "tenderTypes": ["CASH"],
                "lines": [ { "nameFr", "nameEn", "qty", "unitPriceCents",
                             "lineTotalCents" } ] } ] }
  ```

## Menu (session-authed; READ-ONLY mirror of each store's menu)

Each store's tablet owns its menu (one-way sync, CONTRACT.md). The portal only
displays what the stores pushed up; there are no menu write endpoints.

- `GET /v1/menu` →
  ```json
  { "categories": [ { "id", "nameFr", "nameEn", "sortOrder" } ],
    "items": [ { "id", "nameFr", "nameEn", "categoryId", "abbrev",
                 "isAlcohol", "active", "photoVersion", "venueId",
                 "variants": [ { "id", "labelFr", "labelEn", "priceCents",
                                 "sortOrder" } ] } ] }
  ```
  (live rows only; `photoVersion` null when no photo — photo URL is
  `/v1/menu/items/{id}/photo?venue={venueId}&v={photoVersion}`.)
- `GET /v1/menu/items/{id}/photo` — binary, ETag = photoVersion

## Staff (session-authed; READ-ONLY mirror of each store's staff)

- `GET /v1/staff` →
  ```json
  { "staff": [ { "id", "name", "role", "active", "overrides": { "refund": true },
                 "venueId" } ],
    "roleGrants": { "MANAGER": { "void": true, … }, "SERVER": { … } },
    "venueGrants": [ { "venueId", "roleGrants": { … } } ],
    "permissions": [ "void", … ] }
  ```
  Staff are per store and managed on the store's tablet; no PIN or PIN hash
  ever reaches the cloud.

## Store-facing (Bearer store API key — never session)

Documented in CONTRACT.md: `GET /v1/store/capabilities` (handshake), `POST /v1/ingest`, `POST /v1/ingest/photos/{itemId}`,
`GET /v1/store/revocations?since=N` (device revocations only), `POST /v1/store/heartbeat`,
`POST /v1/store/pairing/claim`.

## Misc

`GET /health` → `{ "status": "ok" }` (no auth — compose healthcheck).
