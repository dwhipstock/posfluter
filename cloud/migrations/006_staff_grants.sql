-- 006: cloud-authoritative staff + a basic predefined grant (permission) system.
-- Staff master (name, BCrypt PIN hash, role, active) and two grant layers: role
-- defaults (per role × permission) and per-staff overrides. All distributed to the
-- store over the existing catalog_changes feed (kinds 'staff' / 'role_grants') and
-- enforced offline on the store. The PIN is only ever the BCrypt hash — the
-- plaintext PIN is never stored nor returned by any endpoint.

CREATE TABLE staff (
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    id TEXT NOT NULL,
    name TEXT NOT NULL,
    role TEXT NOT NULL,
    pin_hash TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    language_code TEXT NOT NULL DEFAULT 'en',
    calendar TEXT NOT NULL DEFAULT 'CE',
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, venue_id, id)
);

-- role defaults: one row per (role, permission); granted is the default for any
-- staff of that role who has no per-staff override for the permission.
CREATE TABLE role_grants (
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    role TEXT NOT NULL,
    permission TEXT NOT NULL,
    granted BOOLEAN NOT NULL,
    PRIMARY KEY (tenant_id, venue_id, role, permission)
);

-- per-staff overrides: presence of a row overrides the role default for that
-- (staff, permission). Absence = inherit the role default.
CREATE TABLE staff_grants (
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    staff_id TEXT NOT NULL,
    permission TEXT NOT NULL,
    granted BOOLEAN NOT NULL,
    PRIMARY KEY (tenant_id, venue_id, staff_id, permission)
);
