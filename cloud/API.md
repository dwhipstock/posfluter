# Cloud portal API (cloud/api ⇄ cloud/web)

REST surface the Next.js portal consumes. JSON everywhere; all money integer
cents; dates `YYYY-MM-DD` (venue-local). In production Caddy serves the
portal at `/` and proxies `/v1/*` + `/health` to the API container, so the
browser talks same-origin (no CORS). In dev, Next rewrites `/v1/*` →
`http://localhost:8081`.

Errors: non-2xx bodies are `{ "error": "human message", "code": "machine_code" }`.
401 `not_authenticated`, 403 `totp_pending` (session exists but TOTP step not
done), 404/409/400 as usual.

## Auth

Session = opaque token in an `HttpOnly; SameSite=Lax; Path=/` cookie
`pos_portal_session` (Secure flag when the request came via https). 30-day
expiry, sliding. Passwords BCrypt. TOTP: RFC 6238, SHA-1, 6 digits, 30 s
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

- `GET /v1/reports/summary`
  ```json
  { "grossCents": 0, "netCents": 0, "vatCents": 0,
    "checkCount": 0, "avgCheckCents": 0,
    "voidCount": 0, "voidAmountCents": 0,
    "corkageCents": 0, "serviceChargeCents": 0,
    "byDay": [ { "date": "2026-07-11", "grossCents": 0, "netCents": 0,
                 "vatCents": 0, "checkCount": 0 } ] }
  ```
- `GET /v1/reports/vat` — the tax-filing report.
  ```json
  { "ratePercent": 7,
    "rows": [ { "date": "2026-07-11", "grossCents": 0, "netCents": 0,
                "vatCents": 0, "checkCount": 0 } ],
    "totals": { "grossCents": 0, "netCents": 0, "vatCents": 0, "checkCount": 0 } }
  ```
  (`vatCents` = Σ store-computed `taxIncludedCents`; `netCents` = gross − vat.)
- `GET /v1/reports/payments` →
  `{ "rows": [ { "type": "CASH", "amountCents": 0, "count": 0 } ], "totalCents": 0 }`
  (type ∈ CASH | CARD | BANK_TRANSFER; amount = Σ amountApplied.)
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

## Menu (session-authed; cloud-authoritative catalog)

- `GET /v1/menu` →
  ```json
  { "categories": [ { "id", "nameFr", "nameEn", "sortOrder" } ],
    "items": [ { "id", "nameFr", "nameEn", "categoryId", "abbrev",
                 "isAlcohol", "active", "photoVersion",
                 "variants": [ { "id", "labelFr", "labelEn", "priceCents",
                                 "sortOrder" } ] } ] }
  ```
  (live rows only; `photoVersion` null when no photo — photo URL is
  `/v1/menu/items/{id}/photo?v={photoVersion}`.)
- `POST /v1/menu/categories` `{ nameFr, nameEn }` → 201 category
- `PATCH /v1/menu/categories/{id}` `{ nameFr?, nameEn? }`
- `DELETE /v1/menu/categories/{id}` — 409 `category_in_use` if items reference it
- `PATCH /v1/menu/categories/order` `{ "orderedIds": [...] }`
- `POST /v1/menu/items`
  `{ nameFr, nameEn, categoryId, abbrev, isAlcohol,
     variants: [ { labelFr, labelEn, priceCents } ] }` → 201 item
  (id = slug of nameEn, uniquified — same rule as the store)
- `PATCH /v1/menu/items/{id}` `{ nameFr?, nameEn?, categoryId?, abbrev?, isAlcohol?, active? }`
- `DELETE /v1/menu/items/{id}` — soft delete
- `POST /v1/menu/items/{id}/variants` `{ labelFr, labelEn, priceCents }`
- `PATCH /v1/menu/items/{id}/variants/{variantId}` `{ labelFr?, labelEn?, priceCents? }`
- `DELETE /v1/menu/items/{id}/variants/{variantId}` — 409 `last_variant` on the last live one
- `PUT /v1/menu/items/{id}/photo` — multipart `photo`, jpeg/png ≤ 2 MB
- `GET /v1/menu/items/{id}/photo` — binary, ETag = photoVersion

Every menu mutation bumps the tenant's catalog version and appends a
`catalog_changes` row (see CONTRACT.md §4) so the store picks it up on its
next poll. Item/variant ids are immutable once created.

## Store-facing (Bearer store API key — never session)

Documented in CONTRACT.md: `POST /v1/ingest`, `POST /v1/ingest/photos/{itemId}`,
`GET /v1/store/catalog/changes?since=N`, `GET /v1/store/photos/{itemId}`.

## Misc

`GET /health` → `{ "status": "ok" }` (no auth — compose healthcheck).
