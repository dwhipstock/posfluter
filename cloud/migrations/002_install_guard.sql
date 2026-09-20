-- 002: pin each venue to its store database. Store check/shift ids are SQLite
-- autoincrements; a recreated store DB restarts them at 1 and would silently
-- upsert over projected history. The store sends a stable install id
-- (CONTRACT §1); the first push records it, a mismatch is refused (409).

ALTER TABLE venues ADD COLUMN store_install_id TEXT NULL;
