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
The two demo clients are **Copper Lantern** (tenant `copperlantern`, two
Montréal pubs, CAD, French/English) and **Sage & Poppy** (tenant `sagepoppy`,
one Los Angeles bottle shop, USD, English/Spanish).

## Stores

One owner (tenant) has many stores (venues), and each store has exactly one
POS tablet (one store database per venue; the cloud pins each venue to one
store installation). Extra stations use the staff app served by that tablet
over the LAN. The demo tenant `copperlantern` has two fictional Montréal
stores: **Copper Lantern — Vieux-Port** (`vieux-port`, the Android tablet) and
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

Sync is one-way, tablet → portal. Each store's tablet owns its menu, staff and
grants: they are created and edited on the tablet (offline), and every change
is pushed up through the outbox so the portal can display it. The portal is
read-only for menu and staff. The only thing a tablet pulls down is device
revocations — the owner's remote lock for a lost terminal. No internet means
only sync pauses; nothing on the tablet waits for it.

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

Venue policy is isolated in a typed configuration. The included configuration is fictional: CAD minor units, Gregorian dates, English/French content, generic tenders, and Québec sales taxes (GST 5% and QST 9.975%) added on top of pre-tax prices as data (`TaxPolicy.AddedTaxes`). Menu, staff, settings, receipts, and uploaded photos remain venue-scoped.

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
