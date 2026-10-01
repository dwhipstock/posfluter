# Architecture

The target application is offline-first and self-contained per POS tablet. Each
tablet runs the Flutter UI, its own Kotlin/Ktor store service, and its own
SQLite database. Sales and other business events are recorded locally first,
then queued in an outbox for optional, asynchronous sync to the cloud API,
PostgreSQL projections, and Next.js owner portal. Internet or cloud outages
must not stop local POS operation.

Multiple tablets are separate store units, not replicas of one shared local
database. They do not sync checks or live transactions with one another, and
none is a designated main tablet. A dining-room tablet and a games-room tablet
can each keep operating independently; the web/owner portal can aggregate
their uploaded data when connectivity is available. Local staff and customer
ordering web clients connect over Wi-Fi to the tablet serving their area; they
need that tablet and Wi-Fi, while the tablet's own POS does not.

Current implementation status (2026-09-23): the first tablet now runs the
Flutter UI, Ktor store service, and SQLite locally. Its prior Mac-hosted store
database, media, and installation identity were imported; the Mac store
container is stopped. The tablet connects to the existing cloud ingest and
owner portal asynchronously. Additional independent tablet uploads and
combined reporting remain Milestone 2.

## Clients and their portals

Each client (a restaurant group, a shop) has its **own** manager portal: its
own domain, sign-in, brand, languages, currency and database — one cloud
database per client, holding one tenant. Every client runs the same API and
web images; the differences are runtime config (`cloud/infra/new-client.sh`,
the brand packs in `cloud/web/brands/`). One edge proxy per server routes each
client's hostname to its own instance. Runbook: `docs/new-client-in-a-day.md`.
The three demo clients are **Copper Lantern** (tenant `copperlantern`, two
Raleigh, NC pubs and a quick-serve counter, USD, English first), **Sage & Poppy** (tenant `sagepoppy`,
one Los Angeles bottle shop, USD, English/Spanish) and **Pronghorn Fuel &
Market** (tenant `pronghorn`, one Texas gas station with a convenience store,
USD, English/Spanish).

## The forecourt (a gas station)

A gas station's store also drives its pumps, still on its own: the store
server talks to the forecourt controller on the LAN through a
`ForecourtAdapter` (the demo's is the forecourt simulator), keeps its own
record of every fuelling it takes money for, and syncs fuel sales up like any
other sale. If the controller can't be reached the pumps show offline and
the shop keeps selling. See `docs/forecourt.md`.

## Stores

One owner (tenant) has many stores (venues), and each store has exactly one
POS tablet (one store database per venue; the cloud pins each venue to one
store installation). Extra stations use the staff app served by that tablet
over the LAN. The demo tenant `copperlantern` has fictional Raleigh, NC
stores: **Copper Lantern — Glenwood South** (`vieux-port`, the Android tablet) and
**Copper Lantern — Plateau** (`plateau`, the desktop build of the same store
server). The store server picks its venue config — display name and first-boot
seed — from `POS_VENUE` (`vieux-port` default, or `plateau`); a store's cloud
venue comes from its API key, never from the store.

The owner portal shows every page in two modes chosen by the header's store
picker and kept in the URL (`?store=<id>`): **All stores** (default) combines
the tenant's stores, each over its own business days, and adds a per-store
breakdown to sales and reports; picking a store shows the same page filtered
to it. `docs/demo-runbook.md` brings up both stores locally.

## Sync direction

**The menu syncs both ways; everything else is one-way, tablet → portal.**

- **Sales, receipts, closed checks, shifts, refunds, stock counts, staff and
  grants** are store-owned: recorded on the tablet first and pushed up through
  the outbox so the portal can report on and display them. The portal is
  read-only for them.
- **The menu** (items, their names in every language, descriptions, category,
  availability/86, sizes and their prices, categories and their order) can be
  edited on the tablet *and* in the manager portal, per store or for "All
  stores" of a chain. Tablet edits (including AI menu setup and its reverts)
  go up through the outbox as before; portal edits come down through a
  per-store **menu feed** the tablet pulls on its sync loop. A store that is
  offline catches up from its cursor when it is back.
- The tablet also pulls device revocations (the owner's remote lock for a lost
  terminal) and, at a retail store, the on-hand hint for counts.

No internet means only sync pauses; nothing on the tablet waits for it.

### How a menu edit is merged (last write wins)

Granularity is **per field**: every synced field (an item's English name, its
Spanish name, a size's price, a category's sort order, the `deleted` flag…) is
a last-write-wins register — a value and the stamp of the write that set it.
Two people editing different fields of the same item both keep their change;
the same field takes the later write, on both sides, whichever arrives first.

Stamps are **hybrid logical clocks** (`<ms>-<counter>-<node>`, compared as
strings). The cloud stamps portal edits with its own clock (never the
browser's). The tablet stamps its edits with its own clock **corrected by the
offset to the cloud's clock**, measured on every menu pull, and never stamps
earlier than a stamp it has seen from the cloud. The cloud re-stamps a tablet
stamp more than 2 minutes in its future (a broken tablet clock) and tells the
tablet, so a bad clock can't win forever. The node part makes two writers
never tie.

**Deletes are soft and take part in the same rule**: deleting sets the
`deleted` register. A thing is deleted while that delete is newer than every
other write to it (an item counts its sizes' writes) — so an edit made on the
other side *after* the delete brings it back, and an edit made before it stays
deleted. No ghosts: a deleted item can't be 86'd back on.

**No echo.** While the tablet applies the feed it goes through the same menu
code a tablet edit uses (`CatalogOps`, `Translations`), so translations,
photos, prices and kitchen routing stay consistent, but every menu event it
writes meanwhile is tagged `origin: cloud` and carries the cloud's stamps: the
cloud ignores those, so nothing applied from the cloud goes back up as a new
edit. When the tablet can't take a change (a category that still has items),
it keeps its own state and stamps that afresh, so its state wins everywhere and
both sides agree again. Everything is **idempotent**: feed entries carry full
state, so a replayed page, a re-sent event or a retried portal request
(`Idempotency-Key`) changes nothing.

**Upgrade and deploy order: cloud first, then stores.** Cloud migration 027 and
store migrations 057–059 are additive. An older store never pulls the menu
feed; the cloud notices (the store hasn't asked) and refuses portal menu edits
for it with a clear "update this store's app" message, while still mirroring
its menu one-way as before. A new store against an older cloud gets a 404 on
the feed and simply stays one-way. Details: `cloud/CONTRACT.md` §10.

### When the menu changes under an open order

The store is the source of truth for orders. A menu change from the portal (or
the tablet) never rewrites what was already ordered:

- **A line keeps the menu as rung** (store migration 059): price and tax facts
  as before, and now its names (every language), size label, category and
  whether its size is shown. The check, bills, receipts and reprints, kitchen
  tickets, split bills, refunds, the `check.closed` event and the store's
  reports all read the line's copy. So a rename, a reprice or a re-translation
  in the middle of service changes only lines added afterwards; a split
  stays consistent; a ticket already sent to the kitchen is never re-printed
  as VOID + ADD because of a rename or a new size.
- **Deleted or 86'd while on an open check**: a portal delete (or 86) goes
  through; the line stays exactly as rung and can still be sent, split, paid,
  refunded and reprinted. The item just can't be rung again: the POS, staff
  phones, the guest QR menu and the kiosk get `409 item_unavailable` (the same
  for a deleted size). A tablet delete still asks for the open checks to be
  settled first, as before.
- **In a cart** (guest QR, kiosk, staff phone): every line carries the price
  the client showed. On submit the store refuses only the lines the menu no
  longer allows — `item_unavailable`, or `price_changed` with the new price
  for the guest to confirm — and takes the rest; all refused is a `409
  lines_rejected`. Never a 500, never a silent drop. Menus poll
  `GET /menu/version` every 15 s and refresh on a change; the kiosk upsell only
  offers what is on sale.
- **A category is deleted only when it is empty** (both in the portal and on
  the tablet): move or delete its items first. If the tablet put an item in it
  meanwhile, the tablet keeps the category and it reappears in the portal.
  Open checks are unaffected either way (their lines keep their category).
- **An offline store** keeps selling what it has; a sale of an item the portal
  deleted meanwhile is valid and reports (store and portal) under the name it
  was sold as.
- **AI menu revert** after the portal changed the same item: refused with the
  list of what changed since (`menu_ai_revert_conflict`); confirmed, the
  revert is a new edit and wins by the same rule. A size deleted since stays
  deleted.
- **Re-adding** a deleted item by the same name makes a new id (old history
  intact); a deleted retail product gives up its barcode so it can be added
  again.
- **Photos** of deleted items are kept for history and receipts; menus don't
  show deleted items.

## Time

Every business timestamp is stored as a UTC instant together with the store's
IANA zone: the tablet keeps the zone in `venue_settings.timezone` (seeded once
from `VENUE_TZ`, `America/New_York` for both demo stores) and the cloud keeps
it on each venue row (`timestamptz` columns). The zone is applied only to show
a time, to group by business day (midnight to midnight in the store's zone,
DST-aware) and to build reports — never the tablet's Android timezone or the
server's. On the wire timestamps are ISO-8601 with the venue offset
(`2026-11-01T01:30:00.000-04:00`), so the repeated hour when clocks fall back
is unambiguous. Rows written before this change were zone-less venue-local
times; both databases converted them once by reading them in their venue's
zone, taking the first occurrence of the repeated fall-back hour.

## On-tablet delivery milestones

1. **Self-contained POS tablet with existing cloud connection:** package the
   store service and SQLite with the Android app and use them for all local POS
   operations. Connect this tablet's existing outbox to the already-deployed
   cloud ingest and owner portal, preserving the current store database and
   installation identity during the move from the Mac-hosted demo. Verify real
   sales and operational events, retry/idempotency, and catch-up after Internet loss.
   Startup and sales must also work with the Mac and Internet disconnected; no
   remote health check or sync failure may block the tablet. The first tablet
   cutover is deployed. Verified on-device: background local service, LAN
   customer menu and photos, inherited catalog and login, test-copy sale and
   receipt, cold start without Wi-Fi, and cloud heartbeat after reconnection.
   Longer screen-off/OS-restart endurance and live sale-to-portal catch-up
   should be checked during continued use.
2. **Additional independent tablets in the web app:** accept uploads from each
   tablet as a distinct data source and provide combined web views where
   appropriate. The current cloud pins one store installation to a venue, so
   this requires identity and aggregation changes. It is not a prerequisite
   for the first tablet's cloud reporting, and tablets do not sync with one
   another.

Venue policy is isolated in a typed configuration. The included configuration is fictional: USD minor units, Gregorian dates, English content (French, Spanish, German, Afrikaans selectable), generic tenders, and North Carolina taxes (NC sales tax 6.75% and Wake County's 1% prepared food tax) added on top of pre-tax prices as data (`TaxPolicy.AddedTaxes`). Menu, staff, settings, receipts, and uploaded photos remain venue-scoped.

The one tender that needs the internet is the optional **Card (Stripe)**
(Stripe Terminal, test mode, simulated reader; `payments/StripeService.kt`,
setup in `docs/demo-runbook.md`). It is off without an `sk_test_` key, is
never contacted at startup or sign-in, and every failure records nothing: the
check stays payable by cash or any other tender. Card payments authorize on
the reader and are captured by the store (`capture_method=manual`) right
before the STRIPE tender is recorded; refunds are made at Stripe first and
refused when Stripe is unreachable. The secret key stays on the store.

Secrets and runtime data are never source artifacts. Local databases, environment files, receipt/bill spools, uploads, caches, and build directories are ignored.

## First-tablet operation

The POS talks to `127.0.0.1:8080` inside its Android device (the Sage & Poppy
app, a separate Android app on the same tablet, uses `:8082`); it does not need
the Mac or Internet to start, sign in, or record sales. A foreground Android
service keeps the local store reachable to staff and customer phones while
the POS UI is backgrounded. Those phones need the same Wi-Fi and use the
tablet's LAN address, currently `http://192.168.1.149:8080`; that address can
change with DHCP. The cloud staff-app redirect learns the current address
from tablet heartbeats. Existing printed table QRs referencing the old Mac
address (`192.168.1.2`) must be regenerated/reprinted; new QRs use the
tablet's current LAN address.

The prior Mac store is intentionally stopped to prevent two copies of the
same installation from uploading or accepting sales. The one-time import
accepts a validated SQLite backup, media ZIP, and cloud settings staged via
`scripts/stage-tablet-migration.sh`; the external staging files are removed
after import. The app-private database is the live source of truth thereafter.
The ignored `.tablet-snapshot.final/` directory holds the cutover backup, and
`.tablet-snapshot.B0SO4I/original-pos.apk` holds the previous APK. Do not
restart the Mac store or reinstall the prior APK after tablet sales without
first reconciling the newer tablet data.
