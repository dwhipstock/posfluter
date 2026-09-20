-- 025: TOTP 2FA + 90-day trusted device for the staff ordering web app (M7).
-- Store-local auth state (like sessions), verified OFFLINE — the staff app asks
-- for an authenticator code once per device, then that device logs in with the
-- PIN alone until the trust expires (~90 days), at which point TOTP is required
-- again. The Flutter terminal keeps PIN-only login. Secrets never leave the store.

-- One TOTP secret per staff member; activated_at is null until the first code
-- verifies (enrollment). A manager reset deletes the row so the staff re-enrolls.
CREATE TABLE IF NOT EXISTS staff_totp (
    user_id VARCHAR(64) NOT NULL PRIMARY KEY,
    secret VARCHAR(64) NOT NULL,
    activated_at TEXT NULL,
    last_step INTEGER NULL,          -- last accepted 30s step; makes each code single-use (replay guard)
    created_at TEXT NOT NULL
);

-- A device the staff member cleared TOTP on; the opaque token lives in the
-- phone's localStorage and lets that (staff, device) pair log in PIN-only until
-- expires_at. Bound to user_id: a stolen token is useless without that staff PIN.
CREATE TABLE IF NOT EXISTS trusted_devices (
    token VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    last_used_at TEXT NULL,
    label VARCHAR(100) NULL
);
