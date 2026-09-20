-- 024: staff active flag + soft-delete, and the local mirror of the cloud grant
-- system (CONTRACT §7). users gains active/deleted_at so the cloud can deactivate
-- or soft-delete a staff member without breaking session/check FKs (the row stays).
-- role_grants + staff_grants mirror the cloud tables; the effective grant is
-- per-staff override ?? role default ?? built-in default, computed and enforced
-- offline on the store. The default role matrix is seeded in code at startup
-- (GrantsRepo.seedDefaultRoleGrantsIfEmpty), single-sourced with the permission list.

ALTER TABLE users ADD COLUMN active BOOLEAN NOT NULL DEFAULT 1;
ALTER TABLE users ADD COLUMN deleted_at TEXT NULL;

CREATE TABLE IF NOT EXISTS role_grants (role VARCHAR(20) NOT NULL, permission VARCHAR(40) NOT NULL, granted BOOLEAN NOT NULL, PRIMARY KEY (role, permission));

CREATE TABLE IF NOT EXISTS staff_grants (staff_id VARCHAR(64) NOT NULL, permission VARCHAR(40) NOT NULL, granted BOOLEAN NOT NULL, PRIMARY KEY (staff_id, permission));
