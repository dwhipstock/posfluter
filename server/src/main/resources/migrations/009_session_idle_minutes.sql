-- 009: session idle timeout becomes an owner-tunable venue setting. Minutes of
-- inactivity before a staff session dies and they must re-login. Previously a
-- code/env-var default (30 then 15). Default 15; the existing venue row inherits
-- it via the column default. The 12h absolute session cap stays in code.

ALTER TABLE venue_settings ADD COLUMN session_idle_minutes INT DEFAULT 15 NOT NULL;
