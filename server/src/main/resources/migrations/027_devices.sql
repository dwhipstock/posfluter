-- 027: paired terminal devices (M8 cloud-hosted store).
-- A terminal exchanges a portal-minted pairing code for a per-device token; only
-- the SHA-256 of the token is stored here — the token itself lives in the
-- terminal's secure storage and never leaves this venue's database. Sessions
-- minted by a paired terminal's PIN login record the device (device_id), so a
-- revoked device's sessions die with it. On-prem stores keep working without
-- devices (POS_REQUIRE_DEVICE_TOKEN off → nothing requires a row here).

CREATE TABLE IF NOT EXISTS devices (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    token_sha256 VARCHAR(64) NOT NULL UNIQUE,
    paired_at TEXT NOT NULL,
    last_seen_at TEXT NULL,
    revoked_at TEXT NULL
);

ALTER TABLE sessions ADD COLUMN device_id VARCHAR(36) NULL;
