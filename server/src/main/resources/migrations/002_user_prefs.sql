-- 002: per-user display preferences — UI language.
-- Stored per-user (not per-device): manager and server share the terminal and
-- each sees their own language after login. English is the default; French is secondary.

ALTER TABLE users ADD COLUMN language_code VARCHAR(8) NOT NULL DEFAULT 'en';

ALTER TABLE users ADD COLUMN calendar VARCHAR(2) NOT NULL DEFAULT 'CE';
