# Architecture

The application is offline-first. A Flutter terminal talks to a venue-local Kotlin/Ktor service backed by SQLite. Completed business events enter an outbox and may sync to the optional cloud API, PostgreSQL projections, and Next.js owner portal.

Venue policy is isolated in a typed configuration. The included configuration is fictional: CAD minor units, Gregorian dates, English/French content, generic tenders, and no preset tax rule. Menu, staff, settings, receipts, and uploaded photos remain venue-scoped.

Secrets and runtime data are never source artifacts. Local databases, environment files, receipt/bill spools, uploads, caches, and build directories are ignored.
