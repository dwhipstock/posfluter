-- 038: retail counter sales (a US liquor store alongside the pubs).
--
-- items gain what a shelf product needs: its UPC barcode (unique when set),
-- whether it is age-restricted (alcohol → ID check before payment), whether
-- it is taxable (snacks and ice can be exempt), and its California
-- Redemption Value inputs: container size ('NONE' | 'SMALL' under 24 oz |
-- 'LARGE' 24 oz or more) and units in the pack. Every existing item keeps the
-- defaults: no barcode, not restricted, taxable, no deposit — the pubs are
-- unchanged.
--
-- check_lines capture the same facts at ring-up time (like unit_price_cents),
-- so a later catalog edit never re-prices a sale: taxable, the deposit per
-- unit sold, and age_restricted.
--
-- age_checks keeps ONLY the outcome of an ID check (method, pass/fail, the
-- customer's age in whole years, the legal age applied, who checked, when).
-- Never a name, date of birth, licence number or address.

ALTER TABLE items ADD COLUMN barcode VARCHAR(32) NULL;
ALTER TABLE items ADD COLUMN age_restricted INT NOT NULL DEFAULT 0;
ALTER TABLE items ADD COLUMN taxable INT NOT NULL DEFAULT 1;
ALTER TABLE items ADD COLUMN crv_size VARCHAR(8) NOT NULL DEFAULT 'NONE';
ALTER TABLE items ADD COLUMN pack_units INT NOT NULL DEFAULT 1;
CREATE UNIQUE INDEX IF NOT EXISTS items_barcode ON items (barcode) WHERE barcode IS NOT NULL;
ALTER TABLE check_lines ADD COLUMN taxable INT NOT NULL DEFAULT 1;
ALTER TABLE check_lines ADD COLUMN deposit_cents BIGINT NOT NULL DEFAULT 0;
ALTER TABLE check_lines ADD COLUMN age_restricted INT NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS age_checks (id INTEGER PRIMARY KEY AUTOINCREMENT, check_id INT NOT NULL, method VARCHAR(8) NOT NULL, passed INT NOT NULL, age_years INT NULL, legal_age INT NOT NULL, reason VARCHAR(20) NULL, checked_by VARCHAR(64) NOT NULL, checked_at TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS age_checks_check_id ON age_checks (check_id);
