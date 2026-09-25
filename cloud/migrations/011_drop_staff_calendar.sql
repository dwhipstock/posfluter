-- 011: retire the unused per-staff calendar-era preference (added in 006).
-- It only ever held one value and is no longer read, written, or sent to stores.
ALTER TABLE staff DROP COLUMN IF EXISTS calendar;
