# The Copper Lantern Pub POS

An independent, offline-first point-of-sale demo for a fictional Canadian pub. The store terminal is Flutter, the local service is Kotlin/Ktor with SQLite, and the optional owner portal uses a Kotlin/PostgreSQL API with a Next.js frontend.

The sample venue uses English by default with complete French localization, CAD prices, Gregorian dates, and the `America/Toronto` timezone. Payment examples are cash, a generic card terminal, and manual bank transfer. The demo makes no jurisdiction-specific tax assumption.

No real venue records, customer photographs, credentials, local databases, build output, or production configuration are included.

## Repository layout

- `client/` — Flutter terminal UI
- `server/` — embedded/local store service and SQLite migrations
- `cloud/api/` — optional multi-tenant reporting and catalog API
- `cloud/web/` — owner portal
- `cloud/migrations/` — PostgreSQL schema
- `scripts/` — local demo, provisioning, backup, and end-to-end helpers
- `docs/` — generic architecture and local demo notes

## Local development

Prerequisites: JDK 17+, Flutter 3.4+, Docker Compose for the full stack, and Node.js for direct portal work.

```bash
cp .env.local.example .env.local
cd server && ./gradlew run
```

In another terminal:

```bash
cd client
flutter pub get
flutter run
```

The demo manager PIN is `1234` and the demo server PIN is `9999`. Change both before using the software outside an isolated demo.

For the complete local stack, see `docs/demo-runbook.md`.

## Sample catalog

The seed creates a large bilingual pub menu: draft, bottled, canned, local craft and imported beer; lager, IPA, stout, cider and alcohol-free options; red, white, rosé, sparkling and dessert wine by glass or bottle; cocktails and mixed drinks; and a broad food menu of appetizers, burgers, sandwiches, mains, salads, vegetarian dishes, desserts and late-night snacks.

## Data safety

Runtime `.env` files, SQLite/PostgreSQL data, uploaded photos, receipts, bills, caches, and build products are ignored. Only fictional seed data and placeholder configuration belong in this repository.
