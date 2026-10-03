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

- `POST /v1/auth/login` `{ "email", "password" }` — `email` also takes the demo
  login's plain username (`DEMO_USER_NAME`, case-insensitive)
  - wrong creds → 401 `bad_credentials` (rate-limited: 10/min per IP+email → 429)
  - the demo login (028) with `PORTAL_DEMO_MODE=on` → `{ "stage": "authenticated" }`
    and the session cookie, no TOTP; with it off → 401 `demo_mode_off` (its
    existing sessions get the same 401). The owner and every non-demo user
    always go through TOTP (unless `TOTP_REQUIRED=false`).
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
- `GET /v1/auth/me` → `{ "email", "displayName", "venueName", "tenantName", "role",
  "demo", "demoMode" }` — `demo`: this is the demo login; `demoMode`:
  `PORTAL_DEMO_MODE` is on (the portal's header badge).

Owner-only (403 `owner_only` for any other role, the demo login included):
`POST /v1/venues/{id}/pairing-codes`, `POST /v1/venues/{id}/devices/{deviceId}/revoke`,
`DELETE /v1/venues/{id}/devices/{deviceId}`.

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
| fuel | `{ venueId, venueName, fuelVolumeMilli, fuelAmountCents, fuelCount, inStoreSalesCents, inStoreCheckCount, fuelMarginCents, fuelMarginMillsPerGallon, inStoreMarginCents, inStoreMarginBasisPoints }` |

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
- `GET /v1/reports/fuel` — a gas station's shop and pumps, with margins
  (CONTRACT §2, Fuel; costs and promotions). Fuel from `fuel.sale` rows whose
  `completedAt` falls in the range (each store's business days): what the
  pumps dispensed, tax-inclusive. A prepay's unused change is a
  `refund.created` (Refunds report) and is not subtracted from fuel again.
  In-store = the lines of closed checks in the range whose `categoryId` is not
  `fuel`, before tax: `grossSalesCents` = Σ `lineTotalCents`, `salesCents` =
  gross − `discountCents` (the checks' promotions).
  ```json
  { "currency": "USD",
    "inStore": { "salesCents": 33710, "grossSalesCents": 34494, "discountCents": 784,
                 "lineCount": 75, "qty": 106, "checkCount": 33,
                 "costedLineCount": 66, "uncostedLineCount": 9, "costedSalesCents": 30485,
                 "costCents": 14447, "marginCents": 16038, "marginBasisPoints": 5261 },
    "inStoreByCategory": [ { "categoryId": "snacks", "nameFr", "nameEn", "salesCents", "qty", "lineCount",
                             "costedSalesCents", "costCents", "marginCents", "marginBasisPoints",
                             "uncostedLineCount", "currency" } ],
    "fuel": { "volumeMilli": 398234, "amountCents": 128355, "count": 31,
              "prepayCount": 8, "prepaidCents": 41000, "prepayRefundCents": 4601,
              "costedCount": 31, "uncostedCount": 0, "costedVolumeMilli": 398234,
              "costedAmountCents": 128355, "costCents": 119349, "marginCents": 9006,
              "marginMillsPerGallon": 226 },
    "byGrade": [ { "grade": "REG", "gradeName": "Regular", "volumeMilli": 219337,
                   "amountCents": 65779, "count": 18, "currency": "USD",
                   "costedCount": 18, "costedVolumeMilli": 219337, "costedAmountCents": 65779,
                   "costCents": 61173, "marginCents": 4606, "marginMillsPerGallon": 210 } ],
    "byVenue": [ { "venueId", "venueName", "fuelVolumeMilli", "fuelAmountCents", "fuelCount",
                   "inStoreSalesCents", "inStoreCheckCount", "currency",
                   "fuelMarginCents", "fuelMarginMillsPerGallon",
                   "inStoreMarginCents", "inStoreMarginBasisPoints" } ],
    "money": { … } }
  ```
  - `volumeMilli` = thousandths of a US gallon. Grade rows are one per (grade,
    currency), ordered Regular, Mid-Grade, Premium, Diesel, then others;
    categories are one per (category, currency), best margin first, those
    with no costed line last.
  - **Margins only where the cost is known** (an older store sends none:
    unknown, never zero). `costed*` is the basis, `uncosted*` counts what was
    left out. Fuel: `marginCents` = costed `amountCents` − `costCents`;
    `marginMillsPerGallon` = `marginCents` × 10 000 ÷ `costedVolumeMilli`,
    half-up, in tenths of a cent (226 = 22.6¢/gal). In-store: a check's
    promotions are shared over its shop lines pro rata to their totals (the
    largest line takes the rounding cent) so every line has a net figure;
    `marginCents` = Σ net of costed lines − Σ `unitCostCents × qty`;
    `marginBasisPoints` = `marginCents` × 10 000 ÷ `costedSalesCents`, half-up
    (5261 = 52.61%). A ratio is `null` when nothing in scope was costed.
  - `fuel` and `inStore` money is combined per the currency rules above
    (ratios then come from the combined, converted figures). A store with no
    fuel sales returns empty `byGrade` and zeros. The portal shows this report
    and a leading KPI row on the dashboard (in-store sales and margin, fuel
    gallons and margin per gallon) when the brand pack sets
    `"features": { "fuel": true }`.
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

## Exports (session-authed; owners and managers)

Whole datasets as files, written by the cloud from the full data (the report
pages' PDF / Excel buttons export what the page shows; these export
everything in scope). Scoped like the reports: the caller's tenant, `venue=`
one store or none for all, `from` / `to` business days (default today) read
in each store's own zone. Viewers get 403 `export_forbidden`.

- `GET /v1/exports/{dataset}.csv` or `.xlsx` — one dataset: `sales` (one row
  per CLOSED / VOID check, a `tax_<CODE>_<RATE>_amount` + `_remit_to` column
  pair per tax charged in range), `sale-lines`, `refunds`, `tenders`,
  `shifts`, `cash-movements`, `menu-items` and `staff` (current state; dates
  ignored; staff = names and roles only), `tax-summary` (by tax and authority,
  per store per day, per store total = the tax report's `byVenue[].taxes`,
  all-stores total = its `byTax`). Money is a plain decimal with a `currency`
  column; times are store wall-clock time with a `timezone` column. Text
  starting with = + - @ tab or CR gets a leading apostrophe; XLSX cells are
  typed strings / numbers / dates, never formulas. Unknown dataset 404
  `unknown_export`; another format 400 `bad_format`.
- `GET /v1/exports/all.zip` — owner only (403 `owner_only`): every dataset as
  CSV plus `README.txt` (each file and column). Without `from` / `to` it covers
  every date. 3 per user per rolling hour (429 + `Retry-After`).
- Rows are counted before anything is sent: over 1,000,000 in one file (the
  zip: 5,000,000) is 413 `export_too_large` — narrow the dates or pick one
  store. Rows stream from a database cursor. Each export is recorded in
  `export_log` (user, dataset, format, stores, dates) and logged by user id.

## Menu (session-authed; two-way with each store, CONTRACT.md §10)

The menu syncs both ways: the stores push their menus up, and owners and
managers can edit them here. Reads below; edits under "Menu edits".

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
  carry `barcode`, `brand`, `subcategory`, `size` (null for the pubs), the
  menu specials (CONTRACT §10 "Specials") `availableDays` (e.g. `["fri","sat"]`;
  null = every day) and `specials` (`[ { "days", "from"?, "to"?, "label"?,
  "prices": { "<variantId>": cents } } ]`; null = none), and the
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

### Menu edits (owners and managers; viewers get 403 `menu_edit_forbidden`)

Scope follows the store picker: `?venue=<id>` edits that store; no venue =
**All stores**, applied to every store that has the thing (a new item or
category goes to every store). Each edit is stamped by the cloud's clock,
merged, and queued for each store (it applies it on its next sync). Every
call may send `Idempotency-Key: <uuid>`: a retry with the same key answers the
first result with `"duplicate": true` and changes nothing.

Every edit answers `MenuEditResult`:
`{ "applied": ["plateau"], "skipped": [{ "venueId": "vieux-port", "reason": "store_not_upgraded" }], "id": "nachos-x7k2", "duplicate": false }`.
Reasons: `store_not_upgraded` (the store's app predates two-way sync),
`not_found`, `category_not_found`, `last_variant`, `category_not_empty`. When
no store could take it, the call fails with that reason as its `code`
(404 `not_found`, 400 `category_not_found`, 409 for the others).

- `POST /v1/menu/items` `{ nameEn, nameFr?, names?: {lang: text}, descriptionEn?, descriptionFr?, categoryId, isAlcohol?, active?, abbrev?, variants: [{ labelEn, labelFr?, priceCents, names? }] }` → 201. The id is the cloud's (`nachos-x7k2`), the same at every store.
- `PATCH /v1/menu/items/{id}` — any of `nameEn, nameFr, names (lang → text, "" removes), descriptionEn, descriptionFr, categoryId, isAlcohol, active (86), abbrev`.
  Menu specials: `availableDays` (day codes `mon`…`sun`; `[]` or all seven = every
  day) and `specials` (the whole list, replaced; `[]` = none; at most 10; each
  `{ days (≥1), from?/to? "HH:mm" both or neither and not equal, label? (≤ 40),
  prices: { variantId: cents 0–9,999,999 } (≥1) }`). Stored and sent to the
  store in the canonical form of CONTRACT §10. 400 `bad_day`, `bad_time`,
  `bad_price`, `special_no_days`, `special_no_price`, `too_many_specials`; a
  special pricing a size the item doesn't have at a store skips that store
  (`size_not_found`; 400 when no store took it).
- `DELETE /v1/menu/items/{id}` — soft; history keeps it.
- `POST /v1/menu/items/{id}/copy?venue={store}` `{ from, categoryId? }` → 201: puts the item `from`
  carries on ONE other store, under the same id (so a later "All stores" edit reaches both): names in
  every language, descriptions, sizes (same size ids) and prices, category (`categoryId` at that store;
  default the same id), alcohol flag, tile badge (`abbrev`), availability, selling days, specials, and
  the photo (a `photo` feed entry, as for a portal AI photo). Every field takes this edit's cloud stamp,
  like a create; a copy that store once had and deleted comes back with only the copied sizes live.
  400 `venue_required` (no `venue`), `same_store`, `category_not_found` (no such category at that
  store: pick one); 404 `bad_venue` / `not_found` (`from` doesn't carry it); 409 `already_on_menu`,
  `store_not_upgraded`. Taking it off one store is the ordinary `DELETE …?venue={store}`.
- `GET /v1/menu/stores?item={id}` → `{ canEdit, stores: [{ venueId, name, editable, pending, failed,
  carries, categoryId?, categories }] }`: every store of the client whatever store is picked (Edit
  item → Stores); any signed-in user.
- `POST /v1/menu/items/{id}/variants` `{ labelEn, labelFr?, priceCents, names? }` → 201;
  `PATCH /v1/menu/items/{id}/variants/{variantId}` `{ labelEn?, labelFr?, priceCents?, sortOrder?, names? }`;
  `DELETE …/variants/{variantId}` (not the last size: 409 `last_variant`).
- `POST /v1/menu/categories` `{ nameEn, nameFr?, names? }` → 201;
  `PATCH /v1/menu/categories/{id}` `{ nameEn?, nameFr?, names?, sortOrder? }`;
  `DELETE /v1/menu/categories/{id}` (only when empty: 409 `category_not_empty`);
  `PUT /v1/menu/categories/order` `{ orderedIds }` (unlisted categories follow).
- `GET /v1/menu/sync-status` → `{ canEdit, role, stores: [{ venueId, name, editable, lastPullAt, pending, failed }] }`:
  `editable` = the store takes portal edits; `pending` = edits it hasn't applied yet;
  `failed` = menu changes the store could not apply (it retries them every sync).

`GET /v1/auth/me` also returns `role` (`owner | manager | viewer`) and `canEditMenu`.

### Menu AI (the Menu page's "Ask AI"; owners and managers, ONE store)

The store's AI menu assistant (docs/ai-menu.md) for the portal. The model only
proposes; the cloud validates the proposal against the store's menu with the
store's own rules (`cloud/api/.../menuai/`, ported from `server/.../aimenu/`),
and **Apply** runs the ticked changes through the portal's menu edits above —
same validation, cloud HLC stamps and menu feed, so the store gets them on its
next sync exactly like a hand edit. Off unless `MENU_AI_GEMINI_API_KEY` is set
(model `MENU_AI_MODEL`, default `gemini-3.5-flash-lite`, low thinking).

Every call but status needs `?venue=<id>` (400 `venue_required` without it,
404 `bad_venue` for another tenant's store), an owner or manager (403
`menu_edit_forbidden`), and the assistant on (409 `menu_ai_disabled`).

- `GET /v1/menu-ai/status` → `{ enabled, canUse, model }` (any signed-in user; `model` only for editors).
- `POST /v1/menu-ai/chat` `{ text, lang? }` → a proposal. `lang` (`en|fr|es|de|af`, the portal's
  language) is the fallback language for the summary and the fixed replies; the model answers in the
  language of the request.
- `POST /v1/menu-ai/chat/voice` multipart `audio` (WAV / OGG / AAC / MP3 / FLAC, ≤ 5 MB; the portal
  sends 16 kHz mono WAV) + `lang` → a proposal with `transcript` (what it heard). Other types: 415
  `menu_ai_audio_type`. The clip is never stored or logged.
- Proposal: `{ proposalId, venueId, currency, model, summary, changes: [{ id, kind, title, category,
  details: [{ field, label, before, after, beforeMinor, afterMinor }], needs }], rejected: [..],
  elapsedMs, refusal, message, bulk, bulkReasons, transcript }`. `kind`: `add_category | add_item |
  update_item | remove_item | rename_category | reorder_categories | set_name`; `field`: `nameEn |
  nameFr | descriptionEn | descriptionFr | category | available | price | name (label = language) |
  order`. With `refusal` (`off_topic | no_change | menu_ai_incomplete | menu_ai_too_many_changes`)
  there are no changes and `message` is the fixed reply (never the model's words).
- `POST /v1/menu-ai/apply` `{ proposalId, changeIds, confirmBulk? }` → `{ applyId, applied,
  createdItemIds, summary }`. All or nothing, one transaction. A ticked item pulls in the new category it
  needs. `bulk` proposals (more than 5 changes ticked, any removal, or a price moved by half or more)
  answer 409 `menu_ai_confirm_required` until `confirmBulk: true`. Only the user who asked can apply
  (404 `menu_ai_expired` otherwise, or after 30 minutes; `menu_ai_already_applied` the second time).
  Removals are the portal's soft delete.
- `POST /v1/menu-ai/revert/{applyId}` → `{ applyId, reverted, skipped }`: puts back every field the apply
  changed (new items and categories removed, removed items restored), through the same edits, with fresh
  stamps. A step whose thing changed or went since is skipped. 409 `menu_ai_already_reverted`.
- Limits: 20 calls per 10 minutes per portal user and per store (429 `menu_ai_too_many` + Retry-After),
  and `MENU_AI_DAILY_CAP` (default 150) per store and per user per rolling 24 h (429
  `menu_ai_daily_limit`). AI errors: `menu_ai_timeout | menu_ai_unavailable | menu_ai_quota |
  menu_ai_auth | menu_ai_error` — the cloud's own short text, never the provider's, never the key.
- Audit: `menu_ai_log` (033) has one row per call, apply and revert — user, store, kind, outcome,
  counts, time taken; never the text, the audio, the prompt or a key. `menu_ai_applies` holds each
  apply's undo.

#### Sales-aware requests (portal only)

The assistant knows the store's item sales ("make my top 5 sellers $2 cheaper on Tuesdays", "86
anything that hasn't sold in 2 weeks", "put our 3 slowest drinks on happy hour Mon–Fri 4–6 at $5",
"which burgers sold best last month?"). Cloud only (`menuai/MenuAiSales.kt`): the store's assistant and
the shared parser are unchanged.

- The model gets a `<sales_data>` table of the ONE store in scope (the caller's tenant): per live item,
  units and revenue over the last 7, 30 and 90 full business days ending yesterday, and the day it last
  sold. Same rows and day rules as `GET /v1/reports/items`: lines of CLOSED checks, by close time, each
  business day from the store zone's midnight. At most 400 rows.
- The model answers such a request with a `sales_select` op (rank top / bottom / unsold / list, by units
  or revenue, a period of 1–90 days or a calendar month, n, the items it narrows to, and what to do).
  The cloud takes it out before the parser, **recomputes the ranking from the real numbers** (ties: the
  other figure, then the name) and uses its own list whatever the model picked (`pick_corrected`). It
  then writes the plain ops itself — `set_specials` (the item's other specials kept), `update_item`
  prices, or `active: false` — which the shared parser validates like any other. Relative prices are per
  size: `menu price − amount`, or a percentage rounded half-up to the cent (to .05 / .95 only when asked).
  A size that would end at or below 0, or not below the menu price (a special), is left out
  (`size_refused`); so is one that already has a special as low over the same days and hours
  (`already_lower`; the store charges the cheapest special in force).
- Proposal additions: `sales: [{ rank, by, n, from, to, days, store, currency, rows: [{ itemId, name,
  category, units, revenueMinor, lastSold }] }]` (what it is based on), `salesNotes: [{ code, item, size,
  n, want }]` (`no_sales | none_match | fewer_items | pick_corrected | size_refused | already_lower |
  item_skipped | too_many | bad_request`) and `answer`. A question gets `answer: true`, no `proposalId`
  and no changes (logged as `answered`); the summary is dropped (the numbers on screen are the
  cloud's). No sales in the period: `no_sales`, nothing proposed (never "86 the whole menu" because a
  store didn't sync).
- How the specials are doing (`rank: specials`, `menuai/MenuAiSalesInsights.kt`): per special, the
  average units a special day (inside its hours) vs a comparable other day at the same hours — the rest
  of its group (weekdays or weekend), else every other day — over the last 8 weeks (`onAvg`, `offAvg`,
  `baseline`, `liftPct`; `enough: false` with fewer than 3 days of either kind or nothing sold on the
  other days). An answer, or (`end_weak`, 4 weeks) a proposal removing each special with enough data
  and under 10% lift (`all_working` when there is none). It assumes each special ran the whole period
  (`assumes_whole_period`). `rank: happy_hour`: the top items inside the menu's happy-hour windows (its
  timed specials; the ones labelled happy hour when there are any) over 30 days, with `sharePct` of each
  item's units sold there; `no_happy_hour` without one.
- Same guard, limits, bulk confirm, apply, undo, roles and audit as any other request.

#### AI item photos (`/v1/menu-ai/photos`)

The store's AI photos (docs/ai-photos.md) for one item of one store: a picture in the store's house
style (prompt ported from `server/.../aiphotos/HouseStyle.kt`: no people, logos, brand names or text;
the item's name is checked by the AI guard first, 422 `menu_ai_photo_name`). Providers: Black Forest
Labs FLUX `flux-2-pro` (`MENU_AI_BFL_API_KEY`), then Gemini `gemini-3.1-flash-image`
(`MENU_AI_GEMINI_API_KEY`) when FLUX can't answer (a content-policy refusal is not retried: 422
`menu_ai_photo_refused`). Off without either key (409 `menu_ai_photos_disabled`; `status.photos`
false). Same caller rules as the assistant (owner / manager, `?venue=`), same 10-minute and daily
limits, plus `MENU_AI_PHOTO_DAILY_CAP` (default 30) pictures per store per rolling 24 h (429
`menu_ai_photo_daily_limit`). A store that never pulled the menu feed: 409 `store_not_upgraded`.

- `POST /v1/menu-ai/photos/generate` `{ itemId, mode: generate | enhance, lang? }` → `{ photoId, itemId,
  itemName, source: ai_generated | ai_enhanced, provider, model, contentType, dataBase64, elapsedMs,
  replaces }`. A preview: nothing changes yet. `enhance` retouches the item's current photo (400
  `menu_ai_photo_none` without one). The picture is checked to be a real JPEG / PNG and kept at most
  2 MB (a larger one is re-encoded). A new preview of the same item replaces the user's earlier one;
  previews expire after an hour.
- `POST /v1/menu-ai/photos/{photoId}/accept` → `{ photoId, itemId, photoVersion, photoSource }`: the item's
  photo now (item_photos, `photoVersion`, `photoSource`), and a `photo` entry in the store's menu feed
  (CONTRACT §10). Only the user who made it (404 `menu_ai_photo_expired`); 409
  `menu_ai_photo_already_accepted` the second time. `&everyStore=1` (the portal's "All stores"):
  the photo also becomes the item's photo at every other store that carries it and takes portal
  edits, each with its own feed entry; the answer's `stores` lists them (the asked one first), and
  Undo puts back each store's own previous photo (a store whose photo changed since keeps it).
- `POST /v1/menu-ai/photos/{photoId}/discard` → `{ photoId, status }`.
- `POST /v1/menu-ai/photos/{photoId}/undo` → puts back the photo it replaced (or removes it when the item
  had none), the same way. 409 `menu_ai_photo_changed` when the item's photo changed since (a store
  upload, another AI photo); 409 `menu_ai_already_reverted`.
- The assistant: a picture request ("generate a picture for the iced tea", "photos for every drink", any
  of the five languages, typed or spoken) comes back in the proposal as `photos: [{ id, itemId, title,
  category, mode, hasPhoto }]` (at most 10, live items only, `enhance` only when the request asks to
  improve the existing photo); the portal then generates each one with the call above. A request with
  only pictures has no `changes` and no `refusal`.
- Audit: `menu_ai_log` kinds `photo` (outcome `proposed`, a provider error code, `refused_name`,
  `photo_daily_limit`), `photo_accept`, `photo_undo`; `menu_ai_photos` (034) holds previews and what each
  accepted photo replaced. Never the prompt or a key.

### Printable menus (`/v1/menu-print`; owners and managers, ONE store)

The Menu page's "Print menus": a print-ready PDF of one store's menu, built here (openhtmltopdf +
PDFBox, embedded OFL fonts) so phone and computer get the same file. The AI (Gemini
`MENU_PRINT_MODEL`, default `gemini-3.5-flash`, on `MENU_AI_GEMINI_API_KEY`) only picks and orders item
ids, groups sections, writes short copy and picks one of the curated styles; every name, size and price
is printed from the menu. Its reply is checked (unknown ids dropped, copy clamped; copy naming a business,
or holding a price, a time, a link or unsafe text is dropped; missing copy = the item's own description).
No key, a 35 s timeout, an error or unreadable JSON: the plain menu. Big menus (60–80 items) go to the
model as a compact list; the menu groups items by category and the AI writes section words and at most 25
blurbs (the rest keep their own descriptions). A reply with a stray brace, a fence or a cut-off end is read
for what is complete (`menu_ai_log` outcome `used_<how>` / `bad_reply_<how>`: ok, ok_wrapped, ok_trailing,
repaired_commas, repaired_truncated, not_json…; never the content). Artwork comes from the AI photo makers
(FLUX `MENU_AI_BFL_API_KEY`, then Gemini); without them, or past the 75 s deadline, each style's own
drawn art. Works with no AI at all.

Every call but status needs `?venue=<id>` (400 `venue_required`, 404 `bad_venue`) and an owner or manager
(403 `menu_edit_forbidden`). Three steps, so the portal can show progress; a job lives 30 minutes, for the
user and store that made it (404 `menu_print_expired` otherwise).

- `GET /v1/menu-print/status` → `{ canUse, ai, art, styles }` (any signed-in user).
- `POST /v1/menu-print/plan` `{ type: full | today | drinks | highlights | flyer, lang: en|fr|es|de|af,
  paper: letter | a4, photos, fillPhotos, savePhotos, notes?, style: auto | classic | modern | chalkboard |
  autumn | summer, brand: { name, primary, accent, text, muted, font, logo? } }` → `{ jobId, style, styleBy:
  manager | ai | notes | default, ai: used | off | fallback, aiReason: not_setup | timeout | unavailable |
  bad_reply | daily_limit | too_big | null, notesIgnored, items, artToMake, artAvailable, elapsedMs }`.
  `brand` is the portal's brand pack (colours `#rrggbb`, font `inter | jakarta | barlow`, logo a PNG / JPEG
  data URL ≤ 2 MB); the cloud checks it and darkens any colour that would read too light. `notes` (≤ 300
  characters) go to the model as delimited data; notes that read like instructions are left out
  (`notesIgnored`). `today` = what the store sells on its current business day (its own zone, the day
  starting at 4 a.m.) with that day's special prices. 409 `menu_print_empty`: nothing to print. 429
  `menu_print_too_many`: more than 12 plans in 10 minutes for this user.
- `POST /v1/menu-print/{jobId}/art` `{ fresh? }` → `{ jobId, made, reused, builtIn, photos, photosSaved,
  elapsedMs }`. The header (or the flyer's page background), one picture per section and, with
  `fillPhotos`, stand-in photos for printed items without one (at most 24), made in parallel. Pictures are
  cached per client, style, menu kind and notes (`menu_print_art`, 037): new wording reuses them,
  `fresh: true` ("New artwork") makes them again. With `savePhotos` the stand-in photos also become the
  items' photos through the AI photo accept path (feed entry, undoable). 429 `menu_print_art_too_many`
  for more than 8 runs that make new pictures in 10 minutes.
- `POST /v1/menu-print/{jobId}/render` → `{ pdf (base64), fileName, pages, previews: [JPEG data URLs, first
  6 pages], ai, aiReason, notesIgnored, items, elapsedMs }`. One PDF is built at a time per server; others
  wait (up to 90 s, then 503 `menu_print_busy`). The flyer is always one page.
- Audit: `menu_ai_log` kinds `print` (the AI call; counts toward `MENU_AI_DAILY_CAP`) and `print_art`.
  Never the notes, the copy, the pictures or a key.

## Rooms (session-authed; two-way with each store, CONTRACT.md §11)

The Rooms page: each restaurant's floor as the point of sale has it, drawn
read-only, plus the AI room assistant. No drag-and-drop editor, on purpose.
ONE store per request (`?venue=<id>`; 400 `venue_required` without it, 404
`bad_venue` for another tenant's store).

- `GET /v1/rooms?venue=<id>` (any signed-in user) → `{ venueId, venueName, editable, lastPullAt, canEdit,
  rooms: [{ id, nameEn, nameFr, names, sortOrder, labelPrefix, tables: [{ id, label, number, x, y, width,
  height, rotation, shape, seats, parentTableId, locked }], objects: [{ id, type, x, y, width, height,
  rotation, labelEn, labelFr, names, icon, shape }] }] }`. `editable`: the store applies portal room
  changes (it pulled the feed with `rooms=1`). `locked`: an open bill at the store — the table stays as is.

### Room AI (owners and managers, ONE store; viewers 403 `menu_edit_forbidden`)

The store's "Set up from picture" and floor "Ask AI" (docs/ai-menu.md) for the
portal — the prompts, rules and parsers PORTED verbatim into
`cloud/api/.../rooms/RoomAiPort.kt` (`RoomAiDriftTest` fails if they drift
from `server/.../aimenu/`). The model only proposes; Apply writes through the
room sync (cloud HLC stamps, menu feed), so the store gets it on its next sync.
Same key, model (`MENU_AI_MODEL`, medium thinking like the store's floor
assistant), limits and audit as the Menu AI (`menu_ai_log` kinds `room_photo |
room_chat | room_voice | room_apply | room_revert`; the daily cap counts menu
and room calls together). Off without a key (409 `menu_ai_disabled`); 409
`store_not_upgraded` for a store that doesn't take room changes yet.

- `POST /v1/room-ai/photo?venue=` multipart `image`, repeated 1–4 times (views of the same room from
  different corners, all read in ONE model call and merged into one room; each a phone photo ≤ 12 MB,
  ≤ 32 MB together: JPEG / PNG, resized to 1600 px here; WebP / HEIC passed as is; else 415
  `room_ai_image_type`; none: 400 `room_ai_no_image`; a 5th: 400 `room_ai_too_many_images`; too big:
  413 `room_ai_image_too_large`) + `name` + `lang` → `{ proposalId, venueId, model, roomName,
  labelPrefix, tables: [{ id, label, x, y, width, height, rotation, shape, seats, number }], objects: [{ id,
  type, x, y, width, height, rotation, labelFr, labelEn, icon, shape }], notes, rejected, elapsedMs,
  refusal, message }`. A NEW room: tables numbered from 1 with the name's first letter. The pictures are
  never stored.
- `POST /v1/room-ai/photo/apply?venue=` `{ proposalId, name? }` → `{ applyId, roomId, applied, summary }`
  (the room, its tables and objects; renamed at apply = relabelled).
- `POST /v1/room-ai/chat?venue=&room=<roomId>` `{ text, lang? }` and `POST /v1/room-ai/chat/voice?venue=&room=`
  multipart `audio` + `lang` → `{ proposalId, venueId, roomId, model, summary, transcript, changes: [{ id,
  kind, title, details: [{ field, label, before, after }] }], tables, objects (the ghost: changed ones keep
  their id, added ones `new-t1` / `new-o1`…), removedTables, removedObjects, rejected (in the request's
  language), existingTables, protectedTables, elapsedMs, refusal, message, bulk }`. `kind`: `add_table |
  update_table | remove_table | add_object | update_object | remove_object`; `field`: `number | shape | seats
  | position | size | rotation`. A table with an open bill (in `protectedTables`) stays as it is: any change
  to it is left out with a `rejected` line, the rest still comes back; when it was all that was asked,
  `refusal: table_locked` with `message` naming it (else `off_topic | no_change`). 404 `room_not_found`.
- `POST /v1/room-ai/apply?venue=` `{ proposalId, confirmed? }` → `{ applyId, roomId, applied, summary }`. The
  plan is checked again against the room NOW (a bill opened since is respected). More than 2 removals:
  409 `menu_ai_confirm_required` until `confirmed: true`. 409 `room_no_change` when nothing is left to do.
- `POST /v1/room-ai/revert/{applyId}?venue=` → `{ applyId, reverted, skipped }` (fresh stamps; a step
  that no longer applies — a table with an open bill now, a room that still holds tables — is skipped).
- A table with an open bill is never moved, reshaped, renumbered or removed: left out of every plan
  (a `rejected` line), refused on write (409 `table_locked`), and refused again at the store if the
  cloud's view was stale.

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
