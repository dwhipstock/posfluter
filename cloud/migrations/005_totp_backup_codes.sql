-- One-time TOTP recovery codes. A single owner who loses/wipes their phone must
-- not be locked out for good: at enrollment we hand out a set of backup codes,
-- store only their SHA-256, and burn each on first use (used_at set).
CREATE TABLE portal_backup_codes (
    id BIGSERIAL PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    user_id BIGINT NOT NULL,
    code_sha256 TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP
);
CREATE UNIQUE INDEX portal_backup_codes_hash ON portal_backup_codes (code_sha256);
CREATE INDEX portal_backup_codes_user ON portal_backup_codes (tenant_id, user_id);
