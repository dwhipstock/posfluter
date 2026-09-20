-- 010: index pairing_codes.expires_at. Every code mint runs a hygiene
-- `DELETE FROM pairing_codes WHERE expires_at < now() - 1 day`; without this
-- index that's a sequential scan. The table stays tiny (codes are single-use and
-- short-lived), so this is cheap insurance rather than a hot-path fix, but it
-- keeps the cleanup O(log n) if minting is ever automated for bulk onboarding.
CREATE INDEX IF NOT EXISTS pairing_codes_expires_at ON pairing_codes (expires_at);
