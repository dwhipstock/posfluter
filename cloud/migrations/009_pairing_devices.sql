-- 009: terminal pairing + device registry (cloud side).
--
-- pairing_codes: short-lived, single-use codes the owner mints in the portal for
-- ONE venue. The store container claims a code synchronously (store API key →
-- venue) when a terminal presents it; used_at flips exactly once under the row
-- lock, so a code can never pair two devices. Only the SHA-256 of the code is
-- stored.
--
-- devices: the cloud's PROJECTION of each venue's device registry. The store
-- container owns the actual tokens (they never leave the venue DB); it reports
-- id/name/paired/last-seen/revoked summaries on every heartbeat. A portal revoke
-- sets revoke_requested_at and emits a device_revocation change down the feed;
-- the store applies it and the next heartbeat confirms revoked = TRUE.

CREATE TABLE pairing_codes (
    id BIGSERIAL PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    code_sha256 TEXT NOT NULL UNIQUE,
    label TEXT NOT NULL DEFAULT '',
    created_by TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP NULL
);
CREATE INDEX pairing_codes_venue ON pairing_codes (tenant_id, venue_id);

CREATE TABLE devices (
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    device_id TEXT NOT NULL,
    name TEXT NOT NULL,
    paired_at TIMESTAMP NULL,
    last_seen_at TIMESTAMP NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    revoke_requested_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, venue_id, device_id)
);
