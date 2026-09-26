# Store ⇄ Cloud contract

The single source of truth for how the store (server/, SQLite, offline-first)
and the cloud (cloud/, Postgres, multi-tenant) talk. Both sides build against
this file; change it here first.

Hard rules this contract encodes:
- **The outbox is the only up-sync source.** Every store mutation already
  writes `sync_outbox` in the same transaction; the pusher drains that table.
  No parallel sync path.
- **The cloud aggregates pre-computed figures — it never recomputes money or
  tax.** The store computes tax at sale time and stamps it on the check.
  Copper Lantern prices are pre-tax and Québec's taxes are added on top
  (`TaxPolicy.AddedTaxes`): GST 5% and QST 9.975%, each on the same
  post-discount taxable base, each rounded half-up to the cent once per check
  (a split's groups share the check's tax, largest remainder). An inclusive
  policy (`TaxPolicy.InclusiveTax`) decomposes the tax out of the price instead.
  Events carry those cents figures; cloud reports are sums of them.
  `net = gross − taxIncluded`, per check, computed by the store.
- **Sync is one-way: store → cloud.** Each store's tablet is authoritative for
  its own menu (items, variants, categories, photos), staff and grants. Edits
  happen on the tablet, offline, and are pushed up as events so the portal can
  *display* them; the cloud never edits or redistributes them. Two small,
  best-effort pulls are the only exceptions: **device revocations** (§4), the
  owner's remote lock for a lost terminal, and a retail store's **on hand per
  product** (§9), a read-only hint for the count screen. Sync never blocks
  startup, a sale, a count or a login — with no internet only sync pauses.
- **All money is integer cents.**
- **Timestamps are instants (contract v2).** Every timestamp on the wire is an
  ISO-8601 instant WITH an offset — the store sends its venue's offset at that
  moment, e.g. `2026-07-11T18:02:11.123-04:00` (a `Z` form is equally valid).
  Both sides store the UTC instant: the store as UTC text in SQLite, the cloud
  as `timestamptz`. The venue's IANA zone lives in the store's
  `venue_settings.timezone` (seeded once from `VENUE_TZ`) and on the cloud
  `venues.timezone`; it is applied only for display, business-day grouping
  (a day runs from the venue's midnight to the next, DST-aware — 23 or 25
  hours on transition days) and hourly reports. Portal API responses carry the
  venue's offset too, so the leading wall-clock part is venue-local.
  *Legacy (v1):* a zone-less `2026-07-11T18:02:11` from an older store is read
  as venue-local; in the repeated fall-back hour it resolves to the FIRST
  occurrence (daylight time). Stored v1 rows were converted with the same rule
  (store migration 031, cloud migration 013).

Contract version: **2** (v1 → v2: zone-less venue-local timestamps became
offset-carrying instants; sync became one-way).

## 0. Capability handshake (before any push)

`GET {CLOUD_SYNC_URL}/v1/store/capabilities` — `Authorization: Bearer {key}`

```json
{ "contractVersion": 2, "timestampFormat": "instant",
  "revocationsPath": "/v1/store/revocations" }
```

A v2 store sends instants a v1 cloud cannot read (it parsed zone-less
local times and fell back to "now" on anything else), so the store asks first:
- `timestampFormat: "instant"` → confirmed for the life of the process; the
  outbox drains normally.
- `404` (a cloud that predates this route) or any other `timestampFormat` →
  the store **holds** its outbox: nothing is pushed, nothing is dropped, the
  HWM does not move, and heartbeats carry the LAN URL but no device registry
  (its timestamps are instants too). It logs the hold once and asks again every
  tick; the first confirmed tick drains everything held.
- A transport error → same as not confirmed, retried next tick.

Selling, logins and startup never wait on the handshake. The revocation pull
(§4) is independent of it.

## 1. Event push (store → cloud)

`POST {CLOUD_SYNC_URL}/v1/ingest`
`Authorization: Bearer {CLOUD_SYNC_API_KEY}` (per-store key, maps to
tenant+venue on the cloud; the store never sends tenant ids).

```json
{
  "installId": "uuid",              // identifies THIS store database (minted into
                                     // sync_state on first sync). The cloud pins the
                                     // venue to it on first push and answers 409
                                     // {"code":"install_mismatch"} for any other —
                                     // a re-seeded store DB restarts check/shift ids
                                     // at 1 and must not silently overwrite history.
  "events": [
    {
      "eventId": "uuid",             // sync_outbox.event_id — dedup key
      "seq": 1234,                    // sync_outbox.id — monotonic per store
      "eventType": "check.closed",
      "aggregateType": "check",
      "aggregateId": "42",
      "createdAt": "2026-07-11T18:02:11.123-04:00",
      "payload": { }                  // JSON object, shapes below
    }
  ]
}
```

Response `200`:
```json
{ "accepted": 37, "duplicates": 3, "highWaterMark": 1234 }
```

Semantics:
- **At-least-once, idempotent.** Cloud dedups on `(tenant_id, event_id)`
  (`ON CONFLICT DO NOTHING`); duplicates are counted and skipped. The store
  may retry any batch any number of times.
- Batches are ordered by `seq` ascending (≤ 200 events; the store also keeps
  a batch's payloads to about 1 MB, so a few big catalog chunks go per push).
  The cloud enforces no request-size or event-count limit of its own (one
  transaction per batch; the API runs with a 512 MB heap), so the store's caps
  are the ones that matter. The cloud applies
  projections in `seq` order within a batch. The store only advances its
  high-water mark (`sync_state` key `push_hwm`) after a 200.
- Every event is stored raw in the cloud `events` table (replayable), then
  projected. Unknown event types are stored and otherwise ignored — never an
  error.
- Any non-200 → the store leaves the HWM alone and retries next tick.
  Offline = nothing to do; the outbox is the queue. A 409 `install_mismatch`
  halts sync loudly until an operator resolves it (infra README
  troubleshooting) — never a silent history rewrite.

## 2. Report-complete payloads (store-side enrichment)

The cloud never joins back to store internals, so the money-bearing events
carry everything reports need. Existing keys keep their names; enrichment is
additive.

### `check.closed` (the sales fact — one per settled check)
```json
{
  "currency": "CAD", "country": "CA", // every cent below is in this currency (see Currency)
  "checkId": 42,
  "tableId": "l13", "tableLabel": "L-8",
  "zoneId": "lower", "zoneNameFr": "Zone inférieure", "zoneNameEn": "Lower",
  "shiftId": 7,                        // omitted if closed outside a shift
  "openedAt": "2026-07-11T18:02:11.000-04:00", "closedAt": "2026-07-11T19:40:03.000-04:00",
  "openedBy": "1234",
  "grandTotalCents": 53486,           // = checks.locked_grand_total_cents (what the guest paid)
  "taxIncludedCents": 6966,           // every tax inside grandTotalCents (store-computed)
  "subtotalCents": 46520,             // pre-tax: grandTotalCents − the taxes added on top
  "taxes": [                          // one entry per tax added on top; [] = none
    { "code": "GST", "labelFr": "TPS", "labelEn": "GST", "ratePercent": "5",
      "registrationNumber": "123456789 RT0001", "amountCents": 2326 },
    { "code": "QST", "labelFr": "TVQ", "labelEn": "QST", "ratePercent": "9.975",
      "registrationNumber": "1234567890 TQ0001", "amountCents": 4640 }
  ],
  "corkageBottles": 0,
  "fees": [ { "code": "corkage", "labelFr": "Droit de bouchon", "labelEn": "Corkage", "amountCents": 20000 } ],
  "lines": [
    {
      "lineId": 91, "itemId": "lantern-lager", "variantId": "lantern-lager:pint",
      "categoryId": "beer-cider",
      "nameFr": "Lager de la Lanterne", "nameEn": "Lantern House Lager",
      "variantLabelFr": "bouteille", "variantLabelEn": "Bottle",
      "qty": 2, "unitPriceCents": 9000, "lineTotalCents": 18000,
      "note": null
    }
  ],
  "tenders": [
    {
      "tenderId": 5, "type": "CASH",
      "amountTenderedCents": 60000, "amountAppliedCents": 53486,
      "roundingAdjustmentCents": -1, "changeCents": 6515, // cash took 534.85
      "groupId": null
    }
  ]
}
```
- `lines` = ACTIVE lines only. An off-menu open line has `itemId`/`variantId`/
  `categoryId` null and carries `displayName` instead of names.
- `lines[].variantLabel*` present only when the item has >1 live variant
  (mirrors receipts).
- On split checks `tenders[].groupId` is set; reports only need `type` +
  amounts, groups are informational.
- **Cash rounding.** Canada and the US no longer make pennies, so a CASH
  payment that settles the balance (of the check, or of its split group)
  rounds to the nearest 5¢ on its last cent digit (1–2 → 0, 3–4 → 5,
  6–7 → 5, 8–9 → 10). Everything else stays exact: prices, fees, every tax,
  `grandTotalCents`, and `amountAppliedCents` (which clears the exact
  balance). `roundingAdjustmentCents` is the signed difference: the cash the
  customer paid is `amountAppliedCents + roundingAdjustmentCents`, and
  `changeCents = amountTenderedCents − that`. It is `0` on card, Stripe and
  transfer tenders, on a partial cash payment, and on stores with
  `cash.rounding=off`. Revenue and tax reports use the exact figures; the
  rounding is reported on its own (API.md).
- **Taxes.** `taxIncludedCents` is every tax inside `grandTotalCents` — the
  historical name now also covers taxes added on top, so `net = gross − tax`
  holds for every store. `taxes` itemises the added ones (the sum of their
  `amountCents` is inside `taxIncludedCents`); `ratePercent` is a decimal
  string. Labels, rate and registration number are as charged, never looked
  up later. `subtotalCents` + the `taxes` = `grandTotalCents`.
- **Older stores** (before store migration 036) send neither `subtotalCents`
  nor `taxes`. The cloud stores their per-tax amounts as NULL and reports show
  0 for them — it never estimates a tax that was not sent.
- **Currency.** Every money event (`check.closed`, `check.voided`,
  `refund.created`, `cash.movement`, `shift.closed`) carries
  `"currency": "CAD" | "USD"` (ISO 4217, the store's own currency) and
  `"country": "CA" | "US"` (ISO 3166). All cents in the payload are minor
  units of that currency; a store never mixes currencies. The cloud stores
  the currency on the row (`checks.currency`, `refunds.currency`, …). An older
  store that sends no `currency` is read as its venue's currency
  (`venues.currency`, which is `CAD` for every store that predates this).
  Reports never add two currencies together (§6).

### Retail sales (a store whose kind is retail)
A retail counter sale is an ordinary `check.closed` on the store's one
register (`tableId` `register-1`, zone `counter`), in the store's currency.
Its lines may also carry `taxable: false` (a tax-exempt food item),
`depositCents` (the container deposit — California CRV — per unit) and
`ageRestricted: true`; a line without them is a pub line (taxable, no
deposit, not restricted). The deposits total as one fee, `code: "crv"`, never
taxed. Item snapshots (below) may carry `barcode`, `ageRestricted`,
`taxable: false`, `crvSize` (`SMALL` | `LARGE`) and `packUnits` — and, for a
big retail catalog, `brand` (the producer), `subcategory` (style, varietal or
spirit type: `"IPA"`, `"Pinot Noir"`, `"Tequila Blanco"`) and `size` (a short
size/pack label: `"12 oz can"`, `"6-pack"`, `"750 ml"`, `"1.75 L"`). All
optional; the cloud mirrors them for the portal's product filters (cloud
migration 021) and an absent key is stored as none.
The cloud keeps the store's **stock** from these sales (API.md, Stock) —
see "Stock" below.

### Stock (retail): `stock.counted`, `stock.received`, refunds
Counting and receiving happen IN THE STORE (the stock app on a phone, or the
counter tablet), offline, and are sent up when submitted (store migration
040). Ids are client-minted UUIDs, so a resend is the same count or
delivery. The cloud keeps ONE ledger per product (cloud migrations 018, 020):

    on hand = last count + received − sold ± adjustments + returned

where every term after "last count" counts only what is dated AFTER that
count (a count sets on hand to its qty as of its count time; never counted →
start from 0). Sales are dated by `closedAt`, deliveries by their receiving
time, adjustments by when they were entered, returns by the refund's
`createdAt`. So a sale closed after the count subtracts, and one closed
before it (even if it syncs later) is already in the counted figure.

`stock.counted` (aggregate `stock_count`, id = the count id):
```json
{ "countId": "0d6b3c1e-…", "name": "Friday count",
  "startedBy": "cashier", "startedAt": "2026-07-20T13:40:00.000-07:00",
  "submittedBy": "cashier", "submittedByName": "Demo Cashier",
  "approvedBy": "manager",              // present when a manager approved a variance
  "submittedAt": "2026-07-20T14:05:00.000-07:00",
  "lines": [ { "itemId": "golden-lager-6", "countedQty": 19,
               "countedAt": "2026-07-20T14:00:00.000-07:00",
               "expectedQty": 21 } ] }  // the store's hint then; absent = no expected qty
```
- `countedQty` is the session's total for the product (summed over every
  phone/tablet that counted it); `0` is a real count. `countedAt` is the last
  time it was counted (never after `submittedAt`; unparseable → `submittedAt`).
- The cloud computes its own expected qty per line — the ledger at
  `countedAt`, before this count — and keeps both for the portal's variance
  view. Re-ingesting a `countId` it already has is a no-op.

`stock.received` (aggregate `stock_receipt`, id = the receipt id):
```json
{ "receiptId": "…", "supplier": "Valley Beverage", "reference": "INV-1042",
  "receivedBy": "cashier", "receivedByName": "Demo Cashier",
  "receivedAt": "2026-07-20T09:00:00.000-07:00",
  "lines": [ { "itemId": "golden-lager-6", "qty": 24 } ] }   // qty > 0, one line per product
```
Each line is a `RECEIVED` movement at `receivedAt` (`source: store`).
Re-ingesting a `receiptId` it already has is a no-op.

**Refunds put stock back.** A by-line `refund.created` names its lines
(`lines: [{ lineId, itemId, qty, amountCents }]`; `itemId` since store
migration 040 — for an older store the cloud resolves it from the sale's own
lines). Those quantities are back on hand at the refund's time. An
amount-only refund names no products and changes no stock.

Deliveries and adjustments entered in the portal keep working and stay in
the cloud. Nothing here ever gates a sale: the store sells regardless of
stock (it may go negative), online or not.

### Fuel (a gas station)
A gas station is a retail store whose counter also settles the pumps. Fuel
reaches the cloud two ways; the cloud keeps both (cloud migration 023).

`fuel.sale` (aggregate `fuel_sale`, id = the store's fuel sale id) — one event
per completed fuelling the store has settled: postpay when the sale that paid
for it closes; prepay when the pump finishes and any unused prepay has been
handed back.
```json
{ "currency": "USD", "country": "US",
  "fuelSaleId": 17,
  "checkId": 42,                  // the sale (check) it was paid on
  "pump": 3, "nozzle": 2,
  "grade": "MID", "gradeName": "Mid-Grade",   // REG Regular, MID Mid-Grade, PRE Premium, DSL Diesel
  "volumeMilli": 10052,           // thousandths of a US gallon (10.052 gal)
  "priceMills": 3299,             // thousandths of a dollar per gallon ($3.299)
  "amountCents": 3316,            // what was dispensed, tax-inclusive (fuel taxes are in the pump price)
  "mode": "PREPAY",               // PREPAY | POSTPAY
  "prepaidCents": 4000,           // PREPAY only: paid up front
  "refundCents": 684,             // PREPAY only: unused prepay handed back (0 when it filled exactly)
  "refundId": 9,                  // PREPAY with a refund only
  "fdcTransactionId": "T-000031",
  "completedAt": "2026-09-26T15:04:05.000-05:00" }
```
- Idempotent by `(store, fuelSaleId)`: a re-sent sale (even under a new event
  id) overwrites the row with the same figures, never adds a second one.
- `amountCents` is the fuel figure. The unused prepay is an ordinary
  `refund.created` (`"reason": "Prepay change"`, and it may carry
  `"fuelSaleId"`) and nets out of sales like any refund; `refundCents` here is
  only a note of it and is never subtracted from fuel again.
- `completedAt` is an instant with the store's offset; a sale belongs to the
  store's business day of `completedAt` (missing → the event's `createdAt`).
  An absent `currency` is the store's.

`check.closed` fuel lines carry `"categoryId": "fuel"`, `"taxable": false` and
a `fuel` object besides the usual line keys:
```json
{ "lineId": 7, "itemId": "fuel-mid", "variantId": "fuel-mid:gal", "categoryId": "fuel",
  "nameEn": "Mid-Grade", "nameFr": "Mid-Grade", "qty": 1,
  "unitPriceCents": 3316, "lineTotalCents": 3316, "taxable": false,
  "fuel": { "pump": 3, "nozzle": 2, "grade": "MID", "volumeMilli": 10052,
            "priceMills": 3299, "mode": "POSTPAY", "fdcTransactionId": "T-000031" } }
```
A prepay line on the sale that paid for it is `"itemId": "fuel-prepay"`,
`"categoryId": "fuel"`, `"unitPriceCents": 4000`, `"fuel": { "pump": 3,
"mode": "PREPAY", "prepaidCents": 4000 }` — no grade or volume yet; those
arrive in `fuel.sale`. The cloud keeps each line's `fuel` object as sent
(`check_lines.fuel`) and counts **in-store sales** as the lines whose
`categoryId` is not `fuel`. Item snapshots for the fuel items have
`categoryId: "fuel"`, `taxable: false`, and may be `active: false` (sold at
the pump, not from the shelf). The Fuel report is API.md, Reports.

### `age.checked`
The outcome of one ID check before age-restricted items were paid for, and
nothing else: `checkId, method (SCAN | MANUAL), passed, ageYears?, legalAge,
reason? (under_age | expired | unreadable | not_confirmed), checkedBy,
checkedAt`. Never a name, date of birth, licence number or address. The cloud
stores it raw (not projected yet).

### `check.voided`
Existing `checkId`/`reason`/`authorizedBy` plus:
`tableId, tableLabel, zoneId, zoneNameFr, zoneNameEn, shiftId?, openedAt,
voidedAt, amountCents, taxIncludedCents, taxes` — amount/tax are the
store-computed totals at void time (the locked totals when payment had
started, else the same pipeline math it shows on screen); `taxes` has the
`check.closed` shape.

### `refund.created`
`refundId, checkId, shiftId?, grossCents, netCents, taxIncludedCents,
taxes, currency, country, tenderType, reason, refundedBy, tableId, tableLabel, zoneId,
zoneNameFr, zoneNameEn, createdAt, lines?` (by-line refunds:
`[{ lineId, itemId?, qty, amountCents }]`, see Stock) (+ `processor`,
`stripePaymentIntentId`, `stripeRefundId` for a card refund through Stripe).
`grossCents` is the money returned, `taxIncludedCents` every tax inside it,
`netCents = grossCents − taxIncludedCents`, and `taxes` the added taxes it
reverses (`check.closed` shape). `roundingAdjustmentCents` (signed, store
migration 039): a CASH refund hands back `grossCents` rounded to the nickel,
and this is the difference (cash back = `grossCents + roundingAdjustmentCents`);
`0` for card / transfer refunds. Gross, net and tax stay exact. Older stores
send no `roundingAdjustmentCents`: it is read as 0. The store reverses each tax in proportion to
the money returned, cumulatively, so a full refund — in one go or in parts —
reverses every tax to the cent. A by-line refund returns the lines' pre-tax
price plus their share of the tax. Reports net refunds out of sales and tax.

### `shift.opened`
Existing keys plus `openedAt`.

### `shift.closed` (the Z-report echo — cloud renders, never recomputes)
Existing `shiftId/closedBy/revenueCents/expectedCashCents/closingCountCents/
overShortCents` plus:
`openedAt, closedAt, openedBy, openingFloatCents, transactionCount,
avgCheckCents, corkageCents,
tenderBreakdown: [{ "type": "CASH", "amountCents": 123400, "count": 9 }]`,
`cashRoundingCents` (signed: the shift's cash-sale rounding less its cash
refunds' rounding; absent from older stores). `expectedCashCents` counts the
rounded cash that actually changed hands. `tenderBreakdown` amounts are the
exact applied amounts.

### Catalog snapshots (menu up-sync)
Every `item.*` event (`item.created`, `item.updated`, `item.deleted`,
`item.variant_added`, `item.variant_updated`, `item.variant_deleted`,
`item.availability_changed`, `item.photo_uploaded`) gains a full
post-mutation snapshot under `"item"`:
```json
"item": {
  "id": "lantern-lager", "nameFr": "Lager de la Lanterne", "nameEn": "Lantern House Lager",
  "categoryId": "beer", "abbrev": "CH", "isAlcohol": true,
  "active": true, "deleted": false,
  "photoVersion": 1736590000000,      // optional hint (PhotoStore mtime); may be null/absent.
                                       // Photo binaries move via §3, never via this field.
  "photoSource": "ai_generated",      // optional: original | ai_generated | ai_enhanced
                                       // (AI menu photos). Absent = unknown/older store; the
                                       // cloud keeps its stored value, like photoVersion.
  "variants": [
    { "id": "lantern-lager:bottle", "labelFr": "bouteille", "labelEn": "Bottle",
      "priceCents": 9000, "sortOrder": 0, "deleted": false }
  ]
}
```
`item.photo_uploaded` also carries a top-level `"source"` (the same provenance
value) for the audit trail. The portal Menu page shows a small "AI" badge on
the two AI values.

(`variants` includes soft-deleted rows with `deleted: true` so the cloud can
mirror deletions.) Every `category.*` event gains
`"category": { "id", "nameFr", "nameEn", "sortOrder", "deleted" }`;
`categories.reordered` gains `"categories": [ …full list… ]`.

### `catalog.snapshot` (bootstrap, possibly chunked)
Written at the store's first-ever sync (`sync_state` flag), carrying the live
catalog. The cloud mirrors it for display; every later menu edit on the
tablet carries its own snapshot (above).

A big catalog (a retail store's ~5,000 products) is sent as SEVERAL
`catalog.snapshot` events of up to ~250 items each; `categories` ride in the
first, and a chunk may carry `chunk` / `chunks` (1-based index / count) for
logs. A store that later adds a batch of products at once (e.g. a catalog
upgrade) sends them the same way. Every snapshot is **additive**: the cloud
upserts the items and categories it carries and never removes what a chunk
leaves out (deletions arrive as `item.deleted` / `category.deleted`). A
product listed twice in one chunk keeps its last copy. An older cloud already
applied snapshots this way, so chunked stores work with it unchanged.

### Legacy echo tag
Before sync became one-way, a store that applied a portal menu edit wrote the
matching `item.*`/`category.*` event with `"origin": "cloud"`. Such events may
still sit in older outboxes; the cloud stores them (audit) and does not apply
them. Current stores never write `origin`.

## 3. Photo up-sync (sideband binary, event-triggered)

Outbox payloads never carry binaries. When the pusher drains an
`item.photo_uploaded` event (no `origin`), after the batch is acked it POSTs
the current photo:

`POST /v1/ingest/photos/{itemId}` — `Authorization: Bearer {key}`,
multipart field `photo` (content-type image/jpeg or image/png).
Idempotent overwrite; best-effort (a failed upload is logged and retried the
next time a photo event for that item is drained; it never blocks the HWM).
The cloud keeps the binary for portal display only.

## 4. Device revocations (cloud → store)

The same store loop polls:

`GET {CLOUD_SYNC_URL}/v1/store/revocations?since={cursor}`
`Authorization: Bearer {key}`

```json
{
  "cursor": 93,
  "changes": [
    { "version": 93, "kind": "device_revocation", "op": "upsert",
      "data": { "deviceId": "3f0c…" } }
  ]
}
```

- Written when the owner revokes a lost terminal in the portal. The store flags
  the device revoked and ends its sessions at once; its next heartbeat reports
  `revoked: true` back to the portal.
- `version` is monotonic; the store applies in `version` order and persists
  the last applied value as `sync_state` key `catalog_cursor` (historical
  name). Empty `changes` → cursor unchanged. Applying is idempotent.
- Venue-scoped: a store key only ever sees its own venue's revocations.
- The feed carries **only** `device_revocation`. A store ignores any other
  `kind` it might meet. The legacy path `/v1/store/catalog/changes` serves the
  same revocation-only feed for tablets not yet updated.
- Against an older cloud that answers `404` on `/v1/store/revocations`, the
  store falls back to `/v1/store/catalog/changes` (same cursor, same shape; the
  catalog/staff rows an older cloud serves there are ignored) and stays on it
  until a handshake (§0) confirms a current cloud.
- A failed pull is logged and retried next tick; it never blocks anything.

There is no catalog, photo, staff or grant download any more: the portal is
read-only for menu and staff, and `GET /v1/store/photos/{itemId}` is gone.
The only other pull is a retail store's on-hand hint (§9).

## 5. Store configuration

| env var | meaning | default |
| --- | --- | --- |
| `CLOUD_SYNC_URL` | cloud API base, e.g. `https://api.example.com` | unset → sync disabled |
| `CLOUD_SYNC_API_KEY` | the store's bearer key | unset → sync disabled |
| `CLOUD_SYNC_INTERVAL_SECONDS` | drain/poll cadence | `10` |
| `CLOUD_STOCK_PULL_SECONDS` | retail only: how often the on-hand hint (§9) is pulled | `300` |
| `POS_CASH_ROUNDING` (tablet: `cash.rounding` in store.properties) | `nickel` rounds a cash payment's final amount to 5¢; `off` charges cash to the cent. Local only, never synced down | `nickel` |
| `POS_STAFF_APP_MFA` (tablet: `staff.app.mfa` in store.properties) | `on` asks staff-app sign-in for an authenticator code after the PIN; `off` is PIN only. Local only, never synced down | `on` |

State lives in the store DB table `sync_state (key TEXT PK, value TEXT)`:
`push_hwm` (last acked outbox row id), `catalog_cursor` (last applied
revocation version), `catalog_snapshot_seq` / `staff_snapshot_seq` (one-time
bootstrap markers) and `install_id`. The §9 hint is cached in the store
table `stock_expected` (replaced on every successful pull).

## 7. Staff + grants (store-owned, pushed up)

Staff (manager/server, PIN auth, roles) and a **basic predefined grant** system
are **owned by each store's tablet**. A manager (anyone with `manage_staff`)
adds, edits, deactivates and deletes staff on the tablet — offline — and
enforcement runs **offline on the store**. Every change is pushed up so the
portal can show each store's staff; the portal cannot edit them. Staff are
per store: the same id at two stores is two different people.

The fixed permission set (10, gate POS actions):
`void, refund, discount_comp, cash_movement, open_shift, close_shift,
price_override, zone_open_close, edit_menu, manage_staff`. Roles: `MANAGER`,
`SERVER`. Effective grant for a staff member =
`per-staff override[perm]` if set, else `role default[role][perm]`, else `false`.
Defaults: MANAGER = all true; SERVER = only `price_override`.

Up-sync events (outbox, §1):

```json
{ "eventType": "staff.created",            // also staff.updated / staff.deleted
  "payload": { "staffId": "camille-tremblay",
    "staff": { "id": "camille-tremblay", "name": "Camille Tremblay", "role": "SERVER",
               "active": true, "deleted": false,
               "overrides": { "refund": true } } } }
{ "eventType": "role_grants.updated",
  "payload": { "roles": {
    "MANAGER": { "void": true, "refund": true, "…": true },
    "SERVER":  { "void": false, "price_override": true, "…": false } } } }
{ "eventType": "staff.snapshot",           // once per store DB, at first sync
  "payload": { "staff": [ …staff snapshots… ], "roles": { …matrix… } } }
```

- Snapshots are full state; the cloud upserts them into its venue-scoped
  `store_staff` / `staff_grants` / `role_grants` projections (idempotent).
- **No PIN hash, and no PIN, ever leaves the tablet.**
- `staff.snapshot` is written once per store database (`staff_snapshot_seq`),
  including on stores that synced before staff were store-owned.

**Store staff API** (gated: the session's user must hold `manage_staff`):
`GET /staff/manage`, `POST /staff/manage {name, role, pin}`,
`PATCH /staff/manage/{id} {name?, role?, active?}`,
`POST /staff/manage/{id}/pin {pin}`, `PUT /staff/manage/{id}/grants {overrides}`,
`DELETE /staff/manage/{id}` (soft), `PUT /roles/grants {roles}`. PINs are 4
digits and unique among live staff (`409 pin_in_use`); deactivating, deleting
or re-PINning a member ends their sessions and trusted staff-app devices.

**Enforcement (store, offline).** A gated action for permission `P` by the
logged-in staff `U` (+ optional inline manager PIN): if `effective(U, P)` → allow
directly; else verify the PIN belongs to some staff `A` with `effective(A, P)` →
allow (the existing manager-PIN override); else `403 manager_approval_required`.
`GET /me` and `POST /login` return the acting user's effective grants so the POS
knows whether to prompt.

**Lockout guard.** The store refuses any staff/grant change that would leave
**no active staff with `manage_staff`** (`409 last_manager`). A store started
with an empty catalog (`POS_SEED=none`) gets one bootstrap manager (PIN 1234)
so someone can sign in. The portal owner (a `portal_users` TOTP account) is
separate from POS staff.

## 6. Cloud identifiers

**One client = one portal instance** (its own database, API, portal and
domain; `cloud/infra/README.md`). A database therefore holds one tenant: its id
is `TENANT_ID` (default `copperlantern`, the first client's; never change it on
an existing database).

Bootstrap (idempotent, from cloud env): the tenant `TENANT_ID` and its stores
from `STORES="<venueId>=<name>,…"` (default: one store, `vieux-port`, named
`VENUE_NAME`); the tenant (group) name is `VENUE_NAME` (default `Copper Lantern`),
re-applied on every boot like the store names; stores are created in `VENUE_TZ` (set on insert only — a later boot never re-zones an existing venue); one store API key per store (`STORE_API_KEY`
for the first store, `STORE_API_KEYS="<venueId>=<key>,…"` for the rest); one
portal admin (`ADMIN_EMAIL`/`ADMIN_PASSWORD`, TOTP enrolled on first login).
Every cloud row and every cloud query is scoped by `tenant_id`; the API key
resolves to (tenant, venue) server-side, so a store never names its venue.

The first client's (Copper Lantern, tenant `copperlantern`) stores are `vieux-port` (Copper Lantern — Vieux-Port, the
Android tablet) and `plateau` (Copper Lantern — Plateau). Cloud migration 014
renamed the original venue id `main` to `vieux-port` in every venue-scoped
table (history, projections, keys, devices, install identity); the tablet's
key keeps working unchanged.

Portal reads take an optional `?venue=<id>`: with it, exactly that store;
without it, all of the tenant's stores combined (each over its own business
days), with a per-store `byVenue` breakdown on the sales reports.

**Stores in other countries** (cloud migration 017). Each store has a
currency, a country and a kind, set from env on boot for the stores listed:
`STORE_CURRENCIES="sage-poppy=USD"`, `STORE_COUNTRIES="sage-poppy=US"`,
`RETAIL_STORES="sage-poppy"`, and `STORE_ZONES="sage-poppy=America/Los_Angeles"`
(the zone, like `VENUE_TZ`, on insert only). Unlisted stores keep CAD / CA /
restaurant. The tenant's reporting currency is `REPORTING_CURRENCY` (default
`CAD`); fixed conversion rates are `FX_<FROM>_<TO>` (e.g. `FX_USD_CAD=1.37`;
the reverse is derived). There are no live rates.

Every report response carries `money`:
`{ currency, approximate, reportingCurrency, currencies, rates, convertible }`.
With one currency in scope (any single store, or a group in one country) the
figures are exact in `currency`. With several ("All stores" across
countries) per-store rows stay exact in their own `currency`, `byCurrency`
gives exact totals per currency, and the combined figures are each
currency's exact sum converted into the reporting currency at `rates` and
then added — `approximate: true`. Without a configured rate
(`convertible: false`) that currency contributes 0 to the combined figure and
only its `byCurrency` row is meaningful. `GET /v1/venues` lists each store's
`currency`, `country` and `kind` with the tenant's `reportingCurrency` and
`rates`.

## 8. Heartbeat (store → cloud, every sync tick)

`POST {CLOUD_SYNC_URL}/v1/store/heartbeat` — `Authorization: Bearer {key}`

```json
{
  "installId": "uuid",                 // refused 409 install_mismatch if it differs
  "lanBaseUrl": "http://192.168.1.50:8080",
  "devices": [ { "id": "…", "name": "Bar tablet", "pairedAt": "…", "lastSeenAt": "…", "revoked": false } ],
  "appVersion": "1.4.0",               // optional
  "contractVersion": 2                 // optional
}
```

- `lanBaseUrl` must be a private-LAN origin, or the venue's own public host.
- `devices` is sent only when it changed (and only after §0 confirms); omitted
  = keep the cloud's mirror as is.
- `appVersion` / `contractVersion` are optional and display-only (the portal's
  Devices page); a beat without them clears them.
- The portal reads the last beat as liveness: **online** under 60 s, **stale**
  up to 10 min (the `/staff-app` redirect still trusts the LAN URL), **offline**
  beyond that or never.

## 9. On hand per product (cloud → store, retail, best effort)

A retail store's count screen shows "expected 12" next to each count and
flags variances. The figure comes from the cloud's ledger, pulled slowly:

`GET {CLOUD_SYNC_URL}/v1/store/stock` — `Authorization: Bearer {key}`

```json
{ "retail": true, "asOf": "2026-07-20T14:10:00.000-07:00",
  "items": [ { "itemId": "golden-lager-6", "onHand": 23 } ] }   // products with any history
```

- Pulled every `CLOUD_STOCK_PULL_SECONDS` (default 300), after the tick's
  drain; only by retail stores. §0 advertises it as `stockPath`.
- The store stamps the figures with the instant just before its first
  outbox event the cloud has NOT acknowledged (or now, when it has them all),
  then applies its own moves since — sales out, deliveries in, counts
  submitted there — so the hint is current even between pulls or offline.
- Read-only and never authoritative: it gates nothing, a count or sale never
  waits for it. Offline, never synced, an older cloud (`404`) or a product
  with no history → the app says "no expected qty" and counting goes on.
- A restaurant gets `{ "retail": false, "items": [] }`.
