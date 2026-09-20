-- 008: staff become TENANT-scoped (group-scoped) with per-venue assignments.
-- A staff member is one identity across all of a tenant's venues (one name, one
-- PIN); which venues they work at — and their role at each — moves to
-- staff_venues. Grant tables stay venue-scoped: overrides and role defaults are
-- an operational concern of each venue. Distribution stays on the
-- catalog_changes feed, but snapshots are now emitted per assigned venue.
--
-- Existing data: every current staff row is single-venue, so each becomes one
-- tenant-scoped identity plus one assignment carrying its old role. The ctid
-- dedup guard is a no-op on real data (one venue live) but keeps the PK change
-- safe if this ever runs on a dirtier database.

CREATE TABLE staff_venues (
    tenant_id TEXT NOT NULL,
    staff_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    role TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, staff_id, venue_id)
);

INSERT INTO staff_venues (tenant_id, staff_id, venue_id, role)
    SELECT tenant_id, id, venue_id, role FROM staff;

ALTER TABLE staff DROP CONSTRAINT staff_pkey;
-- Collapse a (tenant_id, id) that existed at multiple venues to ONE identity
-- before the new PK. On real data every staff id is single-venue, so this
-- deletes ZERO rows (verified on prod — 008 already ran there with no effect).
-- If a dirtier restore ever has the same id at multiple venues with differing
-- name/PIN, this keeps the FRESHEST identity (greatest updated_at), so the
-- discard is deterministic and predictable rather than an arbitrary physical
-- ctid; ctid is only the final tiebreak when updated_at is identical.
DELETE FROM staff a USING staff b
    WHERE a.tenant_id = b.tenant_id AND a.id = b.id
      AND (a.updated_at < b.updated_at
           OR (a.updated_at = b.updated_at AND a.ctid > b.ctid));
ALTER TABLE staff ADD PRIMARY KEY (tenant_id, id);
ALTER TABLE staff DROP COLUMN venue_id;
ALTER TABLE staff DROP COLUMN role;

-- The venue's public hostname label (<subdomain>.<base domain>) for the
-- cloud-hosted store container. NULL = venue has no cloud store (on-prem).
ALTER TABLE venues ADD COLUMN subdomain TEXT NULL;
CREATE UNIQUE INDEX venues_subdomain_unique ON venues (subdomain) WHERE subdomain IS NOT NULL;
