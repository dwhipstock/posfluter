-- Zone label prefix: every table's label is "{prefix}-{n}", so a label can never
-- drift out of its zone (no more B1 in Lower) and the readable customer-menu URL
-- /m/{zone}/{n} is derivable from the zone + the number staff already see.
--
-- Backfill: first letter of the English name uppercased is the default (Upper->U,
-- Outside->O, Lower->L, Bar front->B all land correctly), then pin the known
-- venue zones explicitly so a future rename can't quietly repoint the prefix.
-- Forward-only; the explicit UPDATEs are idempotent (fixed target values).
ALTER TABLE zones ADD COLUMN label_prefix VARCHAR(8) NOT NULL DEFAULT '';

UPDATE zones SET label_prefix = UPPER(SUBSTR(name_en, 1, 1)) WHERE label_prefix = '';
UPDATE zones SET label_prefix = 'U' WHERE id = 'upper';
UPDATE zones SET label_prefix = 'O' WHERE id = 'outside';
UPDATE zones SET label_prefix = 'L' WHERE id = 'lower';
UPDATE zones SET label_prefix = 'B' WHERE id = 'bar-front';
