-- 013: every timestamp becomes an instant (timestamptz). Until now they were
-- zone-less TIMESTAMPs holding venue-local wall time (store-reported business
-- times) or the API process's wall time (cloud bookkeeping). The venue's zone
-- stays on venues.timezone and is applied only for display, business-day
-- grouping and reports. The wire contract now carries ISO-8601 instants with an
-- offset (CONTRACT.md v2).
--
-- 1) Store-reported business times are venue-local: each row is interpreted in
--    ITS venue's zone. The repeated hour when clocks fall back (01:00-01:59
--    happens twice) is ambiguous in the old data; the rule is deterministic —
--    the EARLIER instant (first occurrence, daylight time), the same rule the
--    store's own migration (031) uses. PostgreSQL alone would pick the later
--    one, hence the helper below. A wall time inside the spring-forward gap
--    (never produced by a real clock) moves forward by the gap.
-- 2) Cloud bookkeeping times (sessions, keys, pairing codes, heartbeats, …)
--    were written by the API process, whose containers run in UTC, so they are
--    interpreted as UTC.
-- schema_migrations.applied_at is migration-runner bookkeeping and is left alone.

CREATE FUNCTION pg_temp.venue_instant(t timestamp, z text) RETURNS timestamptz LANGUAGE sql IMMUTABLE AS $fn$ SELECT CASE WHEN t IS NULL THEN NULL WHEN ((t AT TIME ZONE z) - interval '1 hour') AT TIME ZONE z = t THEN (t AT TIME ZONE z) - interval '1 hour' ELSE t AT TIME ZONE z END $fn$;

CREATE FUNCTION pg_temp.zone_of(tenant text, venue text) RETURNS text LANGUAGE sql STABLE AS $fn$ SELECT COALESCE((SELECT timezone FROM venues v WHERE v.tenant_id = tenant AND v.id = venue), 'America/New_York') $fn$;

-- (1) venue-local business times
ALTER TABLE events ALTER COLUMN store_created_at TYPE timestamptz USING pg_temp.venue_instant(store_created_at, pg_temp.zone_of(tenant_id, venue_id));
ALTER TABLE checks ALTER COLUMN opened_at TYPE timestamptz USING pg_temp.venue_instant(opened_at, pg_temp.zone_of(tenant_id, venue_id));
ALTER TABLE checks ALTER COLUMN closed_at TYPE timestamptz USING pg_temp.venue_instant(closed_at, pg_temp.zone_of(tenant_id, venue_id));
ALTER TABLE check_tenders ALTER COLUMN tendered_at TYPE timestamptz USING pg_temp.venue_instant(tendered_at, pg_temp.zone_of(tenant_id, venue_id));
ALTER TABLE shifts ALTER COLUMN opened_at TYPE timestamptz USING pg_temp.venue_instant(opened_at, pg_temp.zone_of(tenant_id, venue_id));
ALTER TABLE shifts ALTER COLUMN closed_at TYPE timestamptz USING pg_temp.venue_instant(closed_at, pg_temp.zone_of(tenant_id, venue_id));
ALTER TABLE refunds ALTER COLUMN created_at TYPE timestamptz USING pg_temp.venue_instant(created_at, pg_temp.zone_of(tenant_id, venue_id));
ALTER TABLE cash_movements ALTER COLUMN created_at TYPE timestamptz USING pg_temp.venue_instant(created_at, pg_temp.zone_of(tenant_id, venue_id));
ALTER TABLE devices ALTER COLUMN paired_at TYPE timestamptz USING pg_temp.venue_instant(paired_at, pg_temp.zone_of(tenant_id, venue_id));
ALTER TABLE devices ALTER COLUMN last_seen_at TYPE timestamptz USING pg_temp.venue_instant(last_seen_at, pg_temp.zone_of(tenant_id, venue_id));

-- (2) cloud bookkeeping times (UTC)
ALTER TABLE tenants ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'UTC';
ALTER TABLE venues ALTER COLUMN store_seen_at TYPE timestamptz USING store_seen_at AT TIME ZONE 'UTC';
ALTER TABLE store_api_keys ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'UTC';
ALTER TABLE store_api_keys ALTER COLUMN last_seen_at TYPE timestamptz USING last_seen_at AT TIME ZONE 'UTC';
ALTER TABLE portal_users ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'UTC';
ALTER TABLE portal_sessions ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'UTC';
ALTER TABLE portal_sessions ALTER COLUMN expires_at TYPE timestamptz USING expires_at AT TIME ZONE 'UTC';
ALTER TABLE portal_sessions ALTER COLUMN last_used_at TYPE timestamptz USING last_used_at AT TIME ZONE 'UTC';
ALTER TABLE login_pending ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'UTC';
ALTER TABLE login_pending ALTER COLUMN expires_at TYPE timestamptz USING expires_at AT TIME ZONE 'UTC';
ALTER TABLE events ALTER COLUMN received_at TYPE timestamptz USING received_at AT TIME ZONE 'UTC';
ALTER TABLE catalog_changes ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'UTC';
ALTER TABLE item_photos ALTER COLUMN updated_at TYPE timestamptz USING updated_at AT TIME ZONE 'UTC';
ALTER TABLE staff ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'UTC';
ALTER TABLE staff ALTER COLUMN updated_at TYPE timestamptz USING updated_at AT TIME ZONE 'UTC';
ALTER TABLE staff_venues ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'UTC';
ALTER TABLE portal_backup_codes ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'UTC';
ALTER TABLE portal_backup_codes ALTER COLUMN used_at TYPE timestamptz USING used_at AT TIME ZONE 'UTC';
ALTER TABLE pairing_codes ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'UTC';
ALTER TABLE pairing_codes ALTER COLUMN expires_at TYPE timestamptz USING expires_at AT TIME ZONE 'UTC';
ALTER TABLE pairing_codes ALTER COLUMN used_at TYPE timestamptz USING used_at AT TIME ZONE 'UTC';
ALTER TABLE devices ALTER COLUMN revoke_requested_at TYPE timestamptz USING revoke_requested_at AT TIME ZONE 'UTC';
ALTER TABLE devices ALTER COLUMN updated_at TYPE timestamptz USING updated_at AT TIME ZONE 'UTC';
ALTER TABLE store_staff ALTER COLUMN updated_at TYPE timestamptz USING updated_at AT TIME ZONE 'UTC';
ALTER TABLE staff_venues ALTER COLUMN created_at SET DEFAULT now();
