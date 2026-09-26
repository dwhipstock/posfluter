-- 024: costs and promotions, for margins (a gas station first; any store may
-- send them). Every column is nullable and NULL means "not sent": an older
-- store's sale has an UNKNOWN cost, never a zero one. Reports compute margin
-- only over rows whose cost is known and count the rest.
--
-- check_lines.unit_cost_cents  the item's cost per unit at ring-up
-- checks.discounts             the promotions taken off before tax, as sent
--                              ([{code, label, amountCents}]); line totals stay gross
-- checks.discount_cents        their sum (NULL = none sent)
-- catalog_items.cost_cents     the item's current cost (item snapshots)
-- fuel_sales.cost_mills        the fuelling's cost per gallon, thousandths of a dollar
-- fuel_sales.cost_cents        the fuelling's cost, cents

ALTER TABLE check_lines ADD COLUMN unit_cost_cents BIGINT NULL;
ALTER TABLE checks ADD COLUMN discounts JSONB NULL;
ALTER TABLE checks ADD COLUMN discount_cents BIGINT NULL;
ALTER TABLE catalog_items ADD COLUMN cost_cents BIGINT NULL;
ALTER TABLE fuel_sales ADD COLUMN cost_mills BIGINT NULL;
ALTER TABLE fuel_sales ADD COLUMN cost_cents BIGINT NULL;
