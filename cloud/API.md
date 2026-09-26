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
| fuel | `{ venueId, venueName, fuelVolumeMilli, fuelAmountCents, fuelCount, inStoreSalesCents, inStoreCheckCount }` |

**Currency (every report).** Every per-store row (`byVenue` entries, list rows
such as journal / refunds / voids / cash movements / shifts / zones / tables)
carries `currency` and is exact in it. Item and category rows are one row per
(id, currency): the same item sold in CAD and USD is two rows. Every response
carries `money: { currency, approximate, reportingCurrency, currencies,
rates: [{ from, to, rate }], convertible }` describing its combined figures
(headline totals, day / hour / tender / reason rows): exact in `currency` when
one currency is in scope, else converted into the reporting currency at the
fixed `rates` (`approximate: true`; `convertible: false` = a rate is missing
and that currency adds 0). Summary, by-venue and tax add
`byCurrency: [{ currency, venueIds, grossCents, netCents, taxCents, checkCount,
avgCheckCents, voidCount, voidAmountCents, refundCount, refundAmountCents,
grossReportingCents }]` (exact); payments adds
`byCurrency: [{ currency, totalCents, rows }]`; tax adds
`byTax: [{ code, labelFr, labelEn, ratePercent, currency, amountCents }]`
(every tax code, sales less refunds) and each `byVenue` entry lists its
`taxes` the same way. `GET /v1/venues` returns each venue's `currency`,
`country` and `kind` (restaurant | retail) plus the tenant's
`reportingCurrency` and `rates`.

**Cash rounding (summary, by-venue, tax, payments, refunds, shifts).** Cash
payments round to the nickel at the store (CONTRACT §2); every revenue, tax
and payment amount above stays the exact figure. The net rounding — the
settling cash payments' `roundingAdjustmentCents` less cash refunds' — is
reported beside them as `cashRoundingCents` (signed cents): on every
summary / by-venue / tax `byVenue` and `byCurrency` entry and every payments
`byVenue` / `byCurrency` entry (exact, in that row's currency); at the top of
summary and payments and in tax `totals` it is exact when one currency is in
scope and `null` when the scope spans currencies — rounding is never converted
or added across CAD and USD. Refund rows and refunds `byVenue` carry
`roundingAdjustmentCents` (cash back − gross); shift rows carry the Z-report's
`cashRoundingCents` (`null` from an older store). A payload without the figure
counts 0.

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
- `GET /v1/reports/fuel` — a gas station's fuel beside its shop (CONTRACT §2,
  Fuel). Fuel from `fuel.sale` rows whose `completedAt` falls in the range
  (each store's business days): what the pumps dispensed, tax-inclusive. A
  prepay's unused change is a `refund.created` (Refunds report) and is not
  subtracted from fuel again. In-store = Σ `lineTotalCents` of the lines of
  closed checks in the range whose `categoryId` is not `fuel` (pre-tax item
  sales).
  ```json
  { "currency": "USD",
    "byGrade": [ { "grade": "REG", "gradeName": "Regular", "volumeMilli": 155217,
                   "amountCents": 46548, "count": 16, "currency": "USD" } ],
    "fuel": { "volumeMilli": 310525, "amountCents": 101581, "count": 26,
              "prepayCount": 8, "prepaidCents": 38000, "prepayRefundCents": 3373 },
    "inStore": { "salesCents": 28455, "lineCount": 59, "qty": 75, "checkCount": 29 },
    "byVenue": [ { "venueId", "venueName", "fuelVolumeMilli", "fuelAmountCents", "fuelCount",
                   "inStoreSalesCents", "inStoreCheckCount", "currency" } ],
    "money": { … } }
  ```
  (`volumeMilli` = thousandths of a US gallon. Grade rows are one per
  (grade, currency), in the order Regular, Mid-Grade, Premium, Diesel, then
  others; `fuel` and `inStore` money is combined per the currency rules above.
  A store with no fuel sales returns empty `byGrade` and zeros. The portal
  shows this report when the brand pack sets `"features": { "fuel": true }`.)
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
  `/v1/menu/items/{id}/photo?venue={venueId}&v={photoVersion}`.) Items also
  carry `barcode`, `brand`, `subcategory`, `size` (null for the pubs), and the
  response carries `total` (products: one per item id across the stores).

  **Paged / filtered** (a retail store has ~5,000 products): any of
  `limit` (1–10000), `offset`, `q`, `category`, `subcategory`, `size` →
  the same shape with `items` = every store's copy of ONE PAGE of matching
  products (sorted by category order, then name), `total` = matching
  products, `offset`, `limit`, and `facets: { categories, subcategories,
  sizes }` — each a list of `{ value, count }` under the OTHER filters
  (subcategories within the picked category, sizes within the picked
  category + subcategory). `q`: every word must start a word of the name
  (either language), brand, subcategory or size; an all-digits word also
  matches inside the barcode (`hazy ipa 6-pack`, `750 ml`, `48723`). No
  parameter = the whole menu, as before. 400 `bad_param` for a bad number.
  The portal's export uses `limit=10000` with the page's filters.
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

## Stock (retail stores; session-authed, store-scoped)

Stock lives in the cloud (migrations 018, 020): a retail store sells
regardless of stock — offline too, and it may go negative — and never keeps
an on-hand figure itself. Per product, one ledger:
**on hand = last count + received − sold ± adjustments + returned**, where
every term after the last count counts only what is dated after it (never
counted → start from 0). Counts and deliveries come from the store
(CONTRACT §2 Stock: `stock.counted`, `stock.received`), deliveries and
adjustments also from the portal; sold = the qty of every CLOSED sale
(voided sales never close); returned = the products of by-line refunds. Only
stores whose `kind` is `retail` have stock; restaurants are left out.
Quantities only — no money, so no currency mixing.

- `GET /v1/stock` (`?venue=` or all retail stores) →
  `{ rows: [{ venueId, itemId, name, categoryId, barcode, active, received, sold,
  adjusted, returned, countedQty?, countedAt?, onHand, reorderLevel, low }],
  byVenue: [{ venueId, venueName, products, onHand, lowCount }], totalOnHand,
  lowCount, retail }`. `received`/`sold`/`adjusted`/`returned` are since the
  last count (`countedQty` at `countedAt`), all time when never counted.
  `low` = on hand at or below the reorder level (no level = never low). The
  same product id in two stores is two rows, never one count. Rows also carry
  `brand`, `subcategory`, `size`.
  Paged / filtered: the menu's parameters (`limit`, `offset`, `q`, `category`,
  `subcategory`, `size`) plus `low=true` → `rows` = one page, `total`,
  `offset`, `limit`, `facets`; the KPIs (`byVenue`, `totalOnHand`,
  `lowCount`) stay the whole scope's. No parameter = every row (`total` =
  rows).
- `GET /v1/stock/low-count` → `{ lowCount, retail }` (the nav badge).
- `GET /v1/stock/counts` → `{ counts: [{ venueId, venueName, countId, name,
  submittedBy, approvedBy?, startedAt?, submittedAt, products, units,
  varianceLines, varianceUnits, lines: [{ itemId, name, counted, expected?,
  variance?, countedAt }] }], retail }` — the latest 50 counts submitted at
  the store(s). `expected` is the ledger at the line's count time, before the
  count; null = the product had no history.
- `GET /v1/stock/receipts` → `{ receipts: [{ venueId, venueName, receiptId,
  supplier, reference, receivedBy, receivedAt, units, lines: [{ itemId, name,
  qty }] }], retail }` — the latest 50 deliveries received at the store(s).
- `GET /v1/stock/reorder-suggestions?days=28&cover=14` → `{ rows: [{ venueId,
  venueName, itemId, name, categoryId, barcode, onHand, soldInWindow,
  avgDaily, target, suggested, reorderLevel, low }], days, coverDays,
  toOrder, units, retail }`. avg daily = units sold in the last `days`
  (14–28) ÷ `days`; target = ⌈avg daily × `cover`⌉ (lead time + days of cover,
  1–120); suggested = target − on hand, never below 0. Sorted by suggested.
  400 `bad_param` outside the ranges. The same paging / filter parameters as
  `/v1/stock`, plus `only=to-order` (products with something to order);
  `toOrder` and `units` stay the whole scope's.
- `POST /v1/stock/movements?venue=<retail store>` `{ itemId, kind, qty, note }`
  — `RECEIVED` (a delivery, qty > 0) or `ADJUSTMENT` (breakage, a correction;
  qty ≠ 0). → the product's updated row. 400 `venue_required` / `not_retail` /
  `bad_qty` / `bad_kind`, 404 `unknown_item`. (Counts come from the store.)
- `PUT /v1/stock/reorder?venue=<retail store>` `{ itemId, reorderLevel }` (null
  clears) → the updated row.
- `GET /v1/stock/movements?venue=<retail store>&itemId=` → the latest 100
  movements `{ id, venueId, itemId, kind (RECEIVED | ADJUSTMENT | COUNT), qty,
  note, createdBy, createdAt, source (portal | store) }`, newest first.
