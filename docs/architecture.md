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

Business timestamps are recorded in the venue's `America/New_York` timezone,
independent of the tablet's Android timezone. The cloud portal's date presets
use the same timezone. Existing timestamp strings remain unchanged when this
policy is deployed; historical corrections require a separate reconciliation.

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

Venue policy is isolated in a typed configuration. The included configuration is fictional: CAD minor units, Gregorian dates, English/French content, generic tenders, and no preset tax rule. Menu, staff, settings, receipts, and uploaded photos remain venue-scoped.

Secrets and runtime data are never source artifacts. Local databases, environment files, receipt/bill spools, uploads, caches, and build directories are ignored.

## First-tablet operation

The POS talks to `127.0.0.1:8080` inside its Android device; it does not need
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
