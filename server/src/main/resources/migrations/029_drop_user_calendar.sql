-- 029: retire the unused per-user calendar-era preference (added in 002).
-- It only ever held one value and nothing reads it any more. SQLite >= 3.35
-- (bundled with sqlite-jdbc 3.47) supports DROP COLUMN; the column has no
-- index or constraint beyond its NOT NULL default.
ALTER TABLE users DROP COLUMN calendar;
