-- 004: session expiry — absolute 12h from issue (expires_at) + sliding 30min
-- (last_used_at, refreshed on each authenticated call). NULLs on pre-existing
-- rows fall back to created_at in code. PIN hashing needs no schema change:
-- SQLite ignores VARCHAR lengths (TEXT affinity); the in-place hash upgrade of
-- plaintext PINs runs in code at startup (AuthService.upgradePlaintextPins).

ALTER TABLE sessions ADD COLUMN expires_at TEXT NULL;

ALTER TABLE sessions ADD COLUMN last_used_at TEXT NULL;
