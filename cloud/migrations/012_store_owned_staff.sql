-- 012: one-way sync. The tablet owns its menu, staff and grants; the cloud only
-- projects what the store pushes up, for display. Staff arrive as store
-- snapshots (staff.* / staff.snapshot events) and are VENUE-scoped — each
-- store's staff list is its own, and ids may repeat across stores. No PIN hash
-- is ever sent to or stored by the cloud for these rows.
--
-- The venue-scoped grant tables (role_grants, staff_grants) are reused as the
-- projection of the store's matrix and per-staff overrides. The former
-- cloud-authored staff tables (staff, staff_venues) are left in place, unused,
-- so no data is destroyed by this migration.

CREATE TABLE store_staff (
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    id TEXT NOT NULL,
    name TEXT NOT NULL,
    role TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, venue_id, id)
);
