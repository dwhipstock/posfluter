-- 030: the venue's IANA timezone lives in the store DB, next to the rest of
-- the venue settings. Blank until the next step seeds it from VENUE_TZ; from
-- then on this row (not the environment) is the store's zone. Timestamps are
-- stored as UTC instants (031); the zone is applied only for display,
-- business-day grouping and reports.
ALTER TABLE venue_settings ADD COLUMN timezone VARCHAR(64) NOT NULL DEFAULT '';
