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
- **Sync is one-way, store → cloud, for everything but the menu.** Sales,
  receipts, closed checks, shifts, refunds, stock, staff and grants are owned
  by each store's tablet and pushed up as events so the portal can report on
  and *display* them. **The menu (items, sizes, categories) syncs both ways
  (§10)**: tablet edits go up through the outbox; manager-portal edits come
  down through a per-store menu feed; both are merged field by field, last
  write wins. Photos go tablet → cloud, except an AI photo a manager accepts
  in the portal, which comes down through the menu feed (§10 "Photos from the
  portal"). The other pulls are **device
  revocations** (§4), the owner's remote lock for a lost terminal, and a retail
  store's **on hand per product** (§9), a read-only hint for the count screen.
  Sync never blocks startup, a sale, a count or a login — with no internet
  only sync pauses.
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

Contract version: **3** (v1 → v2: zone-less venue-local timestamps became
offset-carrying instants; sync became one-way. v2 → v3: the menu syncs both
ways, §10 — additive: a v2 store keeps working against a v3 cloud, one-way).

## 0. Capability handshake (before any push)

`GET {CLOUD_SYNC_URL}/v1/store/capabilities` — `Authorization: Bearer {key}`

```json
{ "contractVersion": 3, "timestampFormat": "instant",
  "revocationsPath": "/v1/store/revocations", "stockPath": "/v1/store/stock",
  "menuSyncPath": "/v1/store/menu/changes" }
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
{ "accepted": 37, "duplicates": 3, "highWaterMark": 1234, "quarantined": 0 }
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
- **One bad event never fails its batch.** The cloud drops what Postgres
  cannot store (U+0000, lone surrogates, other control characters except tab
  and line breaks) from every string first. An event that still cannot be
  stored or projected is rolled back alone (a savepoint), kept as it came in
  `ingest_quarantine` with the error (to look at and replay), logged, and
  counted in `quarantined`; the rest of the batch is stored and the batch is
  acknowledged. Only if setting it aside fails too does the batch fail.
- The store also sends only storable text (no U+0000), and if the cloud keeps
  refusing a batch (an HTTP answer, 3 ticks running) it sends that batch one
  event at a time: an event refused alone while the event after it goes
  through is set aside (`sync_state` key `push_quarantine`, its seq; it stays
  in the outbox) and the drain goes on. A cloud that refuses everything gets
  nothing set aside: the store waits.
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
  ],                                  // optional per entry: "remitTo": who the store pays it to
                                      // ("NCDOR", "Wake County"); the tax report shows it and
                                      // keeps one row per code AND rate
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

### Carry-out sales (a table-service restaurant)
A carry-out (to-go) order is an ordinary `check.closed` on the store's
off-floor carry-out location: `tableId` `carry-out-1`, `tableLabel`
`Carry-out`, zone `carry-out` (`zoneNameEn` `Carry-out`, `zoneNameFr`
`À emporter`). Nothing new in the payload: its order number and the
call-in customer's name / phone stay in the store. The carry-out zone is
never in the floor's zone list (nor in `catalog.snapshot` zone names), so the
portal sees it only through its sales.

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
  "costMills": 2699,              // optional: cost per gallon, thousandths of a dollar
  "costCents": 2713,              // optional: this fuelling's cost, cents
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

**Costs and promotions (for margins; any store, all optional — cloud
migration 024).** An absent cost is *unknown*, never zero: reports compute a
margin only over what has a cost and count the rest.
- `check.closed` lines may carry `"unitCostCents": 123` — the item's cost per
  unit at ring-up (a fuel line carries its fuel cost). Line cost =
  `unitCostCents × qty`.
- `check.closed` may carry `"discounts": [{ "code": "energy-2for5",
  "label": "2 for $5 energy drinks", "amountCents": 98 }]` — promotions taken
  off before tax (in-store lines only). Line totals stay gross; net in-store
  sales = Σ non-fuel `lineTotalCents` − Σ `discounts[].amountCents`. The cloud
  keeps the list (`checks.discounts`) and its sum (`checks.discount_cents`).
- Item snapshots may carry `"costCents": 123` (the item's current cost).
- `fuel.sale` may carry `costMills` and `costCents` (above). Fuel margin =
  `amountCents − costCents`.

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
mirror deletions.) A current store (contract v3) adds `"clock": { field: stamp }`
to every item, variant and category snapshot — the write stamp of each synced
field (§10); the cloud merges by it. A snapshot without `clock` (an older
store) is mirrored as before: what it says wins. Every `category.*` event gains
`"category": { "id", "nameFr", "nameEn", "sortOrder", "deleted" }`;
`categories.reordered` gains `"categories": [ …full list… ]`.

**Extra names (`names`).** Every item snapshot, every variant inside its
`variants`, and every category snapshot (in `category.*`,
`categories.reordered` and `catalog.snapshot`) carries
`"names": { "es": "Cerveza", "de": "Bier" }`: the store's names beyond
`nameFr`/`nameEn` (its `translations` table). A current store always sends the
key. When it is present it **replaces** every stored extra name of that item /
variant / category (`{}` clears them); when it is absent (an older store) the
cloud keeps what it has. Blank texts are ignored. Zones: the first
`catalog.snapshot` chunk carries `"zones": [ { "id": "upper", "names": {…} } ]`,
and a rename sends `zone.renamed` with `{ "zoneId": "upper", "names": {…} }`
(it may also carry `nameFr`/`nameEn`; only `names` is mirrored, same replace
rule). The cloud stores them in `catalog_names` (migration 026) and returns
them as `names` on `/v1/menu` items, variants and categories, on the items
report (`names`, `categoryNames`), the categories report (`names`) and the
tables report (zones `names`, tables `zoneNames`). The portal shows the
reader's language when present, else English, else French.

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

### The `origin: "cloud"` tag
A store that applies a portal menu edit (§10) no longer queues the matching
`item.*`/`category.*` events at all: the cloud already has that state, and
storing the echoes only grew its history. Events from older stores (and from
before one-way sync) may still carry `"origin": "cloud"`: the cloud stores them
(audit) and never applies them. Every other event has no `origin`.

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

There is no photo, staff or grant download: the portal is read-only for
staff, and `GET /v1/store/photos/{itemId}` is gone. The menu comes down
through its own feed (§10), not this one; the other pull is a retail store's
on-hand hint (§9).

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
revocation version), `menu_cursor` (last applied menu feed seq, §10),
`menu_hlc` / `menu_node` / `menu_clock_offset_ms` (the menu clock, §10),
`menu_version` (bumped by every menu change; `GET /menu/version`),
`catalog_snapshot_seq` / `staff_snapshot_seq` (one-time bootstrap markers) and
`install_id`. The menu registers are in the store table `menu_sync_clocks`. The §9 hint is cached in the store
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

## 10. Two-way menu sync (contract v3)

The menu — items (names in every language, descriptions, category,
availability, alcohol flag, abbreviation), their sizes (labels, price, order)
and categories (names, order) — can be edited on the tablet and in the
manager portal. Photos, retail shelf facts (barcode, brand, CRV…) and costs
stay tablet-owned (one-way, §2/§3) — except a portal AI photo ("Photos from the
portal" below).

### Registers and stamps
Every synced field is a **last-write-wins register**: its value and the stamp
of the write that set it. Fields:

| thing | fields |
| --- | --- |
| item | `nameFr nameEn descriptionFr descriptionEn categoryId abbrev isAlcohol active deleted availableDays specials` |
| variant | `labelFr labelEn priceCents sortOrder deleted` |
| category | `nameFr nameEn sortOrder deleted` |

plus `names.<lang>` for every name beyond fr/en (a removed name is `null`).
Granularity is per field: concurrent edits of different fields both land.

A stamp is a **hybrid logical clock**, `<13-digit ms>-<4-digit counter>-<node>`,
compared as plain strings; `""` means "set before two-way sync" (store
migration 058 / cloud migration 027 baselines). The cloud's node is `cloud`;
a store's is `s<random>`, so two writers never tie.
- The **cloud** stamps a portal edit with its own clock (persisted per tenant,
  `menu_hlc`), after every stamp it has issued or seen.
- A **store** stamps with its own clock **plus the offset to the cloud's**
  (`serverTimeMs` of every feed page, half the round trip each way), and
  **never goes backwards**: when its wall clock is behind its last stamp (set
  back, NTP, a tablet clock changed while offline) it carries on from the last
  stamp (and logs the jump).
- Both clocks: past counter 9999 the time part moves on by 1 ms and the
  counter starts again at 0, so a burst of writes never ties.
- **Skew guard:** a store stamp more than 2 minutes ahead of the cloud's clock
  is re-stamped with the cloud's on ingest, and the store gets a feed entry
  with `"restamp": true`; for an equal value it adopts the cloud's stamp.

### Specials (store migration 064, cloud migration 036)
Two item fields, each ONE last-write-wins value (the whole list), in a
canonical JSON form so both sides compare the same text:

- `availableDays`: the business days the item is sold, day codes
  `mon tue wed thu fri sat sun`, distinct, **Monday first**:
  `["fri","sat"]`. `null` (or absent) = every day; all seven is written `null`.
- `specials`: the item's day prices, `null` (or absent) = none, else a list
  (at most 10) of objects with keys in THIS order, optional keys left out:
  ```json
  [ { "days": ["mon","tue","wed","thu","fri"], "from": "16:00", "to": "18:00",
      "label": "Happy hour", "prices": { "lantern-lager:pint": 500 } } ]
  ```
  `days` as above (at least one); `from`/`to` "HH:mm" 24 h, both or neither
  (neither = the whole business day), never equal; `label` optional, trimmed,
  ≤ 40 characters (no label: the reader's language names it — "Happy hour"
  with a window, else "Tuesday special"); `prices` size id → cents (≥ 0,
  keys sorted, at least one). Duplicates are dropped.

Meaning (store side, `sdk/MenuSpecials.kt`): days are the venue's business
days in its own zone, a business day running 4 a.m. → 4 a.m. (1 a.m. Saturday
is still Friday night); a window is half-open on the venue's wall clock
(16:00–18:00: 18:00 sharp is the menu price), `to` before `from` runs past
midnight. A line's price is decided when it is rung (the special's when one
is in force and cheaper; the cheapest of several) and kept on the line.
Outside its days an item can't be rung (409 `item_unavailable`, with
`availableDays`). Snapshots leave both keys out when null. A store whose
database predates them baselines them `null` at `""` (064), like 058.

### Merge
For each field, the write with the greater stamp wins (two `""` stamps: the
store's value, as the one-way mirror always did). **Deletes** are the
`deleted` register; a thing is deleted while its `deleted` write is newer than
every other write to it — an item counts its sizes' writes — so a later edit
on the other side brings it back (`deleted` becomes `false` at that edit's
stamp, the same on both sides), an earlier one stays deleted. Merging is
idempotent, commutative and order-independent: replays and duplicates are
harmless.

- **The category order is one value.** A reorder (tablet `categories.reordered`
  or portal `PUT /v1/menu/categories/order`) stamps EVERY category's
  `sortOrder` with the same stamp, moved or not, so the later drag wins whole.
- **A live dish needs a live category.** When a store applies a dish (a portal
  create, a later edit that revives it, a move) into a category it deleted, it
  brings the category back from its tombstone (its registers) and that
  revival goes up as the store's own, freshly stamped edit.
- **A losing store write is corrected.** When a store snapshot's stamped field
  loses on the cloud (an older stamp than the cloud's), the cloud appends the
  merged state to that store's feed (`origin` `correction`), so the store
  converges instead of keeping its value forever.
- Cloud writers take the tenant's menu lock (`menu_hlc` row) **before**
  reading: a store push and a portal edit of the same thing never lose either.

### Up: store → cloud
Unchanged events (§2) with `clock` on every snapshot. The store stamps, in
the outbox writer, exactly the fields whose value changed — whatever code path
changed them (menu editor, 86, AI menu setup or revert, translations, a seed
upgrade). A `catalog.snapshot` stamps a never-seen field `""` (baseline).

### Down: cloud → store
`GET {CLOUD_SYNC_URL}/v1/store/menu/changes?since={cursor}&epoch={epoch}&failed={n}` — `Authorization: Bearer {key}`

```json
{ "cursor": 57, "serverTimeMs": 1790000000000, "epoch": "16384-9f2c…",
  "changes": [
    { "seq": 57, "entity": "item", "id": "nachos-x7k2",
      "data": { "id": "nachos-x7k2", "nameFr": "Nachos", "nameEn": "Nachos", "descriptionFr": "",
                "descriptionEn": "", "categoryId": "starters", "abbrev": "NA", "isAlcohol": false,
                "active": true, "deleted": false, "names": { "es": "Nachos" },
                "clock": { "nameEn": "1790000000000-0000-cloud", "...": "..." },
                "variants": [ { "id": "nachos-x7k2:regular", "labelFr": "Régulier", "labelEn": "Regular",
                                "priceCents": 1200, "sortOrder": 0, "deleted": false, "names": {},
                                "clock": { "priceCents": "1790000000000-0000-cloud" } } ] } },
    { "seq": 58, "entity": "category", "id": "starters", "data": { "id": "starters", "...": "...", "clock": {} } }
  ] }
```
- Per store (`menu_feed`), in `seq` order, ≤ 200 per page; empty `changes` →
  cursor unchanged. Each entry is the thing's **full state with clocks** after
  a portal edit (or a restamp, or a correction), so applying is a merge and a
  replay is a no-op. Hence an entry followed by a newer one of the same thing
  is left out of the page (restamps always stay): a store catching up applies
  each thing once. A dish may then come before the newer entry of its
  category; the store retries what failed once more at the end of the page.
- **Epoch** (cloud migration 029): `<database oid>-<random id>`; it changes
  when the cloud database is restored into a new database or reset. The store
  sends the epoch it has (`epoch`, absent on first pull); when it differs, or
  `since` is past the newest entry for the store (the feed went back), the
  cloud serves the feed **from the start** (and records cursor 0 for the
  portal's count). On a new epoch the store also re-sends its whole menu
  (`catalog.snapshot`, its own stamps), since the restored cloud may have lost
  what it pushed after the backup.
- `failed`: menu changes the store could not apply. The store keeps each one
  (`sync_state` `menu_failed`), logs it and retries it on every pull until it
  goes through; the portal's sync status shows the count (`failed`).
- Pulled every sync tick after the drain; the store pages until caught up,
  applies each page in one transaction with its cursor (`sync_state`
  `menu_cursor`), merges per field and brings its rows to the merged state
  **through the tablet's own menu code** (`CatalogOps`, `Translations`), so
  translations, photos, prices and kitchen routing stay consistent. Menu
  events it writes meanwhile are not queued for the cloud (no echo, §2).
- A change the store can't take — a category that still has items, a size
  that would be the item's last — is refused there; the store re-stamps its
  own state now, so its state wins everywhere. A portal delete of an item or
  size that is on an open check **is** taken: the check line keeps it as rung
  (store migration 059).
- Asking for the feed is how a store says it speaks v3: the cloud records
  `venues.menu_sync_at` / `menu_cursor` (portal "waiting for the store" count).
- An older cloud answers 404: the store asks again in 5 minutes and stays
  one-way meanwhile.

### Photos from the portal (cloud migration 034)
An AI photo a manager accepts in the portal (or the photo an Undo there puts
back) is written to `item_photos` like a store upload and appended to that
store's feed as `{ "entity": "photo", "id": "<itemId>", "data": { "itemId",
"deleted": false, "version", "contentType", "bytes", "sha256", "source" } }`
— never the bytes. An Undo to "no photo" is `{ "itemId", "deleted": true }`.
Only the newest entry per item is served, like any other thing.

- The store queues the entry with the page (same transaction as the cursor,
  `sync_state` `menu_photos_pending`), then, outside it, fetches
  `GET /v1/store/menu/photos/{itemId}` (Bearer) → the binary,
  `X-Photo-Version`, `X-Photo-Source`; 404 = no photo.
- It applies the photo only when `X-Photo-Version` equals the entry's
  `version` (otherwise a newer write superseded it — this store's own upload,
  which reached the cloud first, or a later portal photo with its own entry),
  the body is at most 2 MB, its bytes are a real JPEG or PNG (magic bytes and
  header dimensions 16–8192 px, whatever the Content-Type says), and its
  type, size and sha-256 match the entry. Anything else is dropped and logged.
- Applied through the tablet's own photo pipeline with the provenance
  (`ai_generated | ai_enhanced`), while applying-cloud: no
  `item.photo_uploaded` is queued, so it is not sent back up.
- A `deleted` entry removes the store's photo once the cloud answers 404.
- Offline or a cloud error: the entry stays queued and is retried every
  pull (dropped after 30 tries, e.g. an item this store never got).
- An older store ignores the `photo` entity (it keeps its own photo; the
  portal shows the new one until that store next uploads a photo of the item).

### Portal edits
API.md, Menu. The portal refuses (409 `store_not_upgraded`) edits for a store
that has never pulled the feed (an older store app): it would never receive
them. In "All stores" mode such stores are skipped and listed.

### Deploy order
Cloud first (migration 027 is additive; a v2 store keeps working one-way),
then the stores (store migrations 057–059 are additive; 058 baselines the
existing menu with `""` stamps, so nothing is re-sent and the first portal
edit wins over the old values).

## 11. Two-way room sync (rooms, tables, floor objects)

The floor — rooms (store zones: names in every language, order, table-label
prefix), dining tables (label, shape, seats, x / y / width / height on the
1000 × 1000 plan, rotation, sub-table link, room) and floor objects (type,
names, icon, shape, geometry, room) — can be changed on the tablet and from
the manager portal's Rooms page (a new room from a photo, a room edited by
the AI assistant). It is §10 again, for three more things:

| thing | fields |
| --- | --- |
| room | `nameFr nameEn sortOrder labelPrefix deleted` + `names.<lang>` |
| table | `zoneId label parentTableId x y width height rotation shape seats deleted` |
| floor_object | `zoneId type x y width height rotation labelFr labelEn icon shape deleted` + `names.<lang>` |

Same registers, stamps, clocks (the store's one menu clock; the cloud's
`menu_hlc` per tenant, its lock taken before reading), merge (per field, the
greater stamp wins, tombstones, a later edit revives), restamp, correction,
epoch and failed-change retry. Off-floor sale locations (the carry-out
register's zone) are not rooms and never sync. Open bills never leave the store.

### Up: store → cloud
`floor.snapshot` (aggregate `floor` / `rooms`):
```json
{ "rooms": [ { "id": "upper", "nameFr": "Salle", "nameEn": "Dining Room", "sortOrder": 0, "labelPrefix": "U",
               "deleted": false, "names": { "es": "Comedor" }, "clock": { "nameEn": "<hlc>", "...": "..." } } ],
  "tables": [ { "id": "t5", "zoneId": "upper", "label": "U-1", "parentTableId": null, "x": 50, "y": 145,
                "width": 65, "height": 65, "rotation": 0, "shape": "ROUND", "seats": 1, "deleted": false,
                "clock": { "x": "<hlc>", "...": "..." } } ],
  "objects": [ ... ],
  "locked": [ "t5", "t5-5" ] }
```
- The store compares its floor with its registers on every sync tick (and
  around every feed page that carries room entries) and sends exactly the
  things whose fields changed — whatever code path changed them (floor-plan
  editor, AI room set-up / floor assistant, a revert, a seed) — at most 250
  things per event. The first time ever it sends the whole floor with `""`
  stamps (baseline: the first portal edit wins over them); after a new feed
  epoch, the whole floor again with its own stamps.
- A deleted thing is frozen: only `deleted` is stamped (a soft-deleted table, a
  hard-deleted room or object).
- `locked`: every table (all rooms) the portal must not move, reshape,
  renumber, re-room or remove — an open bill on it or on one of its
  sub-tables. Always the full list; the cloud marks the rest free.

### Down: cloud → store
Room changes are entries of the menu feed (§10) with `entity` `room`, `table`
or `floor_object` — one cursor, one epoch. A store that applies them pulls
with `&rooms=1`; the cloud records `venues.rooms_sync_at`, and the portal
refuses room changes (409 `store_not_upgraded`) for a store that never did. An
older store ignores such entries (it logs and skips unknown entities).

The store merges and brings its rows to the merged state, with these refusals
(the store stays the authority for open bills):
- a table with an open bill (on it or a sub-table) keeps everything but its
  seats — never moved, reshaped, renumbered, re-roomed or removed;
- a table other live tables anchor to (sub-tables) is not removed;
- a room that still holds live tables or objects is not removed.
After a refusal the store re-stamps its own values fresh, so they win on the
cloud too (the portal shows the table where it really is). A payload it can't
take (an unknown shape or type, a table in a room it doesn't have yet) is kept
in `menu_failed` and retried every pull. Nothing applied from the cloud goes
back up (no echo): the rows then equal the registers.

### Deploy order
Cloud first (migration 035 is additive: `floor_things`, `room_ai_applies`,
`venues.rooms_sync_at`, the feed's entity check); then the stores (no store
migration: the registers share `menu_sync_clocks`; the first sync tick
baselines the floor).
