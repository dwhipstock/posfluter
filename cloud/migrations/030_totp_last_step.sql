-- 029: TOTP replay guard (red-team). The 30-second step of the last code that
-- signed this portal user in; a code is accepted only for a LATER step, so a
-- code seen over a shoulder or replayed from a log can't sign in a second time.
--
-- NOTE for merges: renumber this file if another 029 lands on main first.
ALTER TABLE portal_users ADD COLUMN IF NOT EXISTS totp_last_step BIGINT;
