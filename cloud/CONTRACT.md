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
- **Sync is one-way: store → cloud.** Each store's tablet is authoritative for
  its own menu (items, variants, categories, photos), staff and grants. Edits
  happen on the tablet, offline, and are pushed up as events so the portal can
  *display* them; the cloud never edits or redistributes them. The single
  exception is **device revocations** (§4): the owner's remote lock for a lost
  terminal is the only data the store pulls down. Sync never blocks startup, a
  sale or a login — with no internet only sync pauses.
- **All money is integer cents.** All timestamps are venue-local ISO-8601
  `LocalDateTime` strings without zone (`2026-07-11T18:02:11`); the venue's
  IANA timezone lives on the cloud `venues` row (America/New_York) and is display
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
      "nameFr": "Lager de la Lanterne", "nameEn": "Lantern House Lager",
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
  "id": "lantern-lager", "nameFr": "Lager de la Lanterne", "nameEn": "Lantern House Lager",
  "categoryId": "beer", "abbrev": "CH", "isAlcohol": true,
  "active": true, "deleted": false,
  "photoVersion": 1736590000000,      // optional hint (PhotoStore mtime); may be null/absent.
                                       // Photo binaries move via §3, never via this field.
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
full live catalog. The cloud mirrors it for display; every later menu edit on
the tablet carries its own snapshot (above).

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

## 4. Device revocations (cloud → store) — the only pull

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
- A failed pull is logged and retried next tick; it never blocks anything.

There is no catalog, photo, staff or grant download any more: the portal is
read-only for menu and staff, and `GET /v1/store/photos/{itemId}` is gone.

## 5. Store configuration

| env var | meaning | default |
| --- | --- | --- |
| `CLOUD_SYNC_URL` | cloud API base, e.g. `https://api.example.com` | unset → sync disabled |
| `CLOUD_SYNC_API_KEY` | the store's bearer key | unset → sync disabled |
| `CLOUD_SYNC_INTERVAL_SECONDS` | drain/poll cadence | `10` |

State lives in the store DB table `sync_state (key TEXT PK, value TEXT)`:
`push_hwm` (last acked outbox row id), `catalog_cursor` (last applied
revocation version), `catalog_snapshot_seq` / `staff_snapshot_seq` (one-time
bootstrap markers) and `install_id`.

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

Bootstrap (idempotent, from cloud env): tenant `copperlantern`, venue `main`
(display "The Copper Lantern Pub", tz `America/New_York`), one store API key
(`STORE_API_KEY`), one portal admin (`ADMIN_EMAIL`/`ADMIN_PASSWORD`, TOTP
enrolled on first login). Every cloud row and every cloud query is scoped by
`tenant_id`; the API key resolves to (tenant, venue) server-side.
