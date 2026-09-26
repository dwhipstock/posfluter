-- 047: what things cost the store, and the deals it runs (Pronghorn first).
--
-- item_variants.cost_cents: what the store pays for one (a size has its own
-- cost); NULL = unknown, never read as free. check_lines.unit_cost_cents
-- freezes it at ring-up like the price, so a margin never moves after the
-- sale. fuel_sales carries the fuel's cost per gallon and the fuelling's cost.
-- checks.locked_discounts_json freezes the promotions a sale got at total
-- lock ([{code,label,labelEs,amountCents,taxableCents}]), like its taxes.
-- Every existing row keeps NULL: the pubs and the bottle shop are unchanged.

ALTER TABLE item_variants ADD COLUMN cost_cents BIGINT NULL;
ALTER TABLE check_lines ADD COLUMN unit_cost_cents BIGINT NULL;
ALTER TABLE fuel_sales ADD COLUMN cost_mills BIGINT NULL;
ALTER TABLE fuel_sales ADD COLUMN cost_cents BIGINT NULL;
ALTER TABLE checks ADD COLUMN locked_discounts_json TEXT NULL;
