# Store ⇄ Cloud contract

The single source of truth for how the store (server/, SQLite, offline-first)
and the cloud (cloud/, Postgres, multi-tenant) talk. Both sides build against
this file; change it here first.

Hard rules this contract encodes:
- **The outbox is the only up-sync source.** Every store mutation already
  writes `sync_outbox` in the same transaction; the pusher drains that table.
  No parallel sync path.
- **The cloud aggregates pre-computed figures — it never recomputes money or
  tax.** When configured, the store decomposes inclusive sales tax at sale time
  (`TaxPolicy.InclusiveVat`: `tax = (base*rate*2 + (100+rate)) / ((100+rate)*2)`,
  half-up at the cents) and stamps it on the check. Events carry those cents
  figures; cloud reports are sums of them. `net = gross − vatIncluded`, per
  check, computed by the store.
- **Cloud is authoritative for the catalog** (items, variants, categories,
  photos). POS edits push up as events; the cloud applies them, bumps its
  version, and redistributes. The store always converges to the cloud's state.
- **All money is integer cents.** All timestamps are venue-local ISO-8601
  `LocalDateTime` strings without zone (`2026-07-11T18:02:11`); the venue's
  IANA timezone lives on the cloud `venues` row (America/Toronto) and is display
  metadata only — bucketing by day/hour uses the naive timestamps as-is.

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
      "createdAt": "2026-07-11T18:02:11",
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
- Batches are ordered by `seq` ascending (≤ 200 events). The cloud applies
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
  "checkId": 42,
  "tableId": "l13", "tableLabel": "L-8",
  "zoneId": "lower", "zoneNameFr": "Zone inférieure", "zoneNameEn": "Lower",
  "shiftId": 7,                        // omitted if closed outside a shift
  "openedAt": "2026-07-11T18:02:11", "closedAt": "2026-07-11T19:40:03",
  "openedBy": "1234",
  "grandTotalCents": 53500,           // = checks.locked_grand_total_cents
  "taxIncludedCents": 6155,           // = checks.locked_tax_included_cents (store-computed)
  "corkageBottles": 0,
  "fees": [ { "code": "corkage", "labelFr": "Frais de bouchon de bouteille", "labelEn": "Corkage", "amountCents": 20000 } ],
  "lines": [
    {
      "lineId": 91, "itemId": "lantern-lager", "variantId": "lantern-lager:pint",
      "categoryId": "draft-beer",
      "nameFr": "éléphant", "nameEn": "Lantern House Lager",
      "variantLabelFr": "bouteille", "variantLabelEn": "Bottle",
      "qty": 2, "unitPriceCents": 9000, "lineTotalCents": 18000,
      "note": null
    }
  ],
  "tenders": [
    {
      "tenderId": 5, "type": "CASH",
      "amountTenderedCents": 60000, "amountAppliedCents": 53500,
      "roundingAdjustmentCents": -50, "changeCents": 6500,
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

### `check.voided`
Existing `checkId`/`reason`/`authorizedBy` plus:
`tableId, tableLabel, zoneId, zoneNameFr, zoneNameEn, shiftId?, openedAt,
voidedAt, amountCents, taxIncludedCents` — amount/tax are the store-computed
totals at void time (voids have no locked totals; the store runs the same
pipeline math it shows on screen).

### `shift.opened`
Existing keys plus `openedAt`.

### `shift.closed` (the Z-report echo — cloud renders, never recomputes)
Existing `shiftId/closedBy/revenueCents/expectedCashCents/closingCountCents/
overShortCents` plus:
`openedAt, closedAt, openedBy, openingFloatCents, transactionCount,
avgCheckCents, corkageCents,
tenderBreakdown: [{ "type": "CASH", "amountCents": 123400, "count": 9 }]`.

### Catalog snapshots (menu up-sync)
Every `item.*` event (`item.created`, `item.updated`, `item.deleted`,
`item.variant_added`, `item.variant_updated`, `item.variant_deleted`,
`item.availability_changed`, `item.photo_uploaded`) gains a full
post-mutation snapshot under `"item"`:
```json
"item": {
  "id": "lantern-lager", "nameFr": "éléphant", "nameEn": "Lantern House Lager",
  "categoryId": "beer", "abbrev": "CH", "isAlcohol": true,
  "active": true, "deleted": false,
  "photoVersion": 1736590000000,      // optional hint (PhotoStore mtime); may be null/absent.
                                       // Photo binaries move via §3/§4, never via this field.
  "variants": [
    { "id": "lantern-lager:bottle", "labelFr": "bouteille", "labelEn": "Bottle",
      "priceCents": 9000, "sortOrder": 0, "deleted": false }
  ]
}
```
(`variants` includes soft-deleted rows with `deleted: true` so the cloud can
mirror deletions.) Every `category.*` event gains
`"category": { "id", "nameFr", "nameEn", "sortOrder", "deleted" }`;
`categories.reordered` gains `"categories": [ …full list… ]`.

### `catalog.snapshot` (one-time bootstrap)
Written once, at the store's first-ever sync (`sync_state` flag), carrying the
full live catalog; the cloud applies it WITHOUT emitting distribution changes.
Because it lands at first connect, any menu edits made in the portal *before*
the store has ever synced are superseded by it — connect the store first,
then edit the menu from the web.

### Echo tag
When the **store applies a cloud-originated catalog change** (§4) it writes
the corresponding `item.*`/`category.*` outbox event **with an extra
`"origin": "cloud"` key**. The cloud stores such events (audit) but skips
catalog application and does NOT emit a new distribution change — this is
what breaks the echo loop. Events without `origin` are POS-originated edits
and are applied + redistributed.

## 3. Photo up-sync (sideband binary, event-triggered)

Outbox payloads never carry binaries. When the pusher drains an
`item.photo_uploaded` event (no `origin`), after the batch is acked it POSTs
the current photo:

`POST /v1/ingest/photos/{itemId}` — `Authorization: Bearer {key}`,
multipart field `photo` (content-type image/jpeg or image/png).
Idempotent overwrite; best-effort (a failed upload is logged and retried the
next time a photo event for that item is drained; it never blocks the HWM).

## 4. Catalog pull (cloud → store)

The same store loop polls:

`GET {CLOUD_SYNC_URL}/v1/store/catalog/changes?since={cursor}`
`Authorization: Bearer {key}`

```json
{
  "cursor": 87,
  "changes": [
    { "version": 86, "kind": "category", "op": "upsert",
      "data": { "id": "beer", "nameFr": "bière", "nameEn": "Beer", "sortOrder": 0, "deleted": false } },
    { "version": 87, "kind": "item", "op": "upsert",
      "data": { …item snapshot, same shape as §2… } },
    { "version": 88, "kind": "item.photo", "op": "upsert",
      "data": { "itemId": "lantern-lager", "photoVersion": 1736590000001 } }
  ]
}
```

- `version` is the cloud's monotonically increasing per-tenant change number;
  the store applies in `version` order and persists the last applied value as
  `sync_state` key `catalog_cursor`. Empty `changes` → cursor unchanged.
- `op: "upsert"` carries the FULL snapshot (not a delta): the store
  insert-or-updates items+variants / categories to exactly that state
  (variant/category rows absent locally are created; snapshot `deleted: true`
  soft-deletes). `op: "delete"` soft-deletes by id.
- `kind: "item.photo"` → the store fetches
  `GET /v1/store/photos/{itemId}` (same auth, binary response + content-type)
  and saves it into its PhotoStore, then sets `items.photo_path`.
- Applying is idempotent: re-applying any prefix of the change stream is a
  no-op. Guards the store already enforces stay enforced locally (e.g. an
  item on an open check line can't be hard-removed — snapshots only
  soft-delete, so this never conflicts).
- After each applied change the store writes the matching outbox event with
  `"origin": "cloud"` (§2), keeping the local audit trail and giving the
  cloud an ack without an echo.

### Conflict rule (cloud-authoritative)
The cloud applies whatever reaches it, in arrival order, and bumps `version`.
A POS edit therefore wins at the cloud until a later web edit (and vice
versa); the store always converges to the cloud's latest state on its next
pull, even if that overwrites a local edit that never reached the cloud
(last-to-reach-cloud wins; the losing edit survives in the event log). Menu
edits are rare and single-owner — convergence beats merge cleverness here.

## 5. Store configuration

| env var | meaning | default |
| --- | --- | --- |
| `CLOUD_SYNC_URL` | cloud API base, e.g. `https://api.example.com` | unset → sync disabled |
| `CLOUD_SYNC_API_KEY` | the store's bearer key | unset → sync disabled |
| `CLOUD_SYNC_INTERVAL_SECONDS` | drain/poll cadence | `10` |

State lives in the store DB table `sync_state (key TEXT PK, value TEXT)`:
`push_hwm` (last acked outbox row id) and `catalog_cursor` (last applied
cloud change version).

## 7. Staff + grants distribution (cloud → store)

Staff (manager/server, PIN auth, roles) and a **basic predefined grant** system
are **cloud-authoritative** and ride the same catalog change feed (§4) — the
owner manages them from the portal, the store converges on its next pull, and
enforcement runs **offline on the store**. Unlike the catalog, staff flow **down
only**: the store never originates staff/grant mutations, so there is no up-sync
and no echo. The store applies these changes into its local `users` table +
`role_grants`/`staff_grants` tables and enforces without the cloud.

The fixed permission set (10, gate POS actions):
`void, refund, discount_comp, cash_movement, open_shift, close_shift,
price_override, zone_open_close, edit_menu, manage_staff`. Roles: `MANAGER`,
`SERVER`. Effective grant for a staff member =
`per-staff override[perm]` if set, else `role default[role][perm]`, else `false`.
Defaults: MANAGER = all true; SERVER = only `price_override`.

Two new `kind`s on `GET /v1/store/catalog/changes`:

```json
{ "version": 91, "kind": "staff", "op": "upsert",
  "data": {
    "id": "server1", "name": "employé (Server)", "role": "SERVER",
    "pinHash": "$2a$10$…",              // BCrypt hash ONLY — never the plaintext PIN
    "active": true, "languageCode": "fr", "calendar": "CE", "deleted": false,
    "overrides": { "refund": true }     // per-staff grant overrides (only keys the owner set)
  } }
{ "version": 92, "kind": "role_grants", "op": "upsert",
  "data": { "roles": {
    "MANAGER": { "void": true, "refund": true, "…": true },
    "SERVER":  { "void": false, "price_override": true, "…": false }
  } } }
```

- `kind: "staff"`, `op: "upsert"` upserts the staff into the store `users` table
  (`pin` = `pinHash`) and replaces that staff's `staff_grants` overrides with
  `data.overrides`. `op: "delete"` (or `deleted: true`) soft-deletes: the row is
  kept (`active=false`, session/check FKs stay valid) and its overrides are cleared.
- `kind: "role_grants"`, `op: "upsert"` replaces the store `role_grants` table
  with the full matrix in `data.roles`.
- No echo: the store does **not** re-emit staff/grant changes to the outbox.

**Enforcement (store, offline).** A gated action for permission `P` by the
logged-in staff `U` (+ optional inline manager PIN): if `effective(U, P)` → allow
directly; else verify the PIN belongs to some staff `A` with `effective(A, P)` →
allow (the existing manager-PIN override); else `403 manager_approval_required`.
`GET /me` and `POST /login` return the acting user's effective grants so the POS
knows whether to prompt.

**Owner lockout guard.** The cloud refuses any staff/grant mutation that would
leave **no active staff with `manage_staff`** (`409 last_manager`). The portal
owner (a `portal_users` TOTP account) is separate from POS staff and is never
locked out.

## 6. Cloud identifiers

Bootstrap (idempotent, from cloud env): tenant `copperlantern`, venue `main`
(display "The Copper Lantern Pub", tz `America/Toronto`), one store API key
(`STORE_API_KEY`), one portal admin (`ADMIN_EMAIL`/`ADMIN_PASSWORD`, TOTP
enrolled on first login). Every cloud row and every cloud query is scoped by
`tenant_id`; the API key resolves to (tenant, venue) server-side.
