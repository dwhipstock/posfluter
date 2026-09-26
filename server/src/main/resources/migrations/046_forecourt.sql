-- 046: the forecourt (a gas station with a convenience store, Pronghorn).
--
-- fuel_sales is the store's own record of every fuelling it takes money for,
-- whatever the pump controller (FDC) says later:
--   POSTPAY  the pump was authorised from the counter, the customer filled up
--            and came inside: the FDC's payable transaction is locked onto the
--            sale as a fuel line (IN_BASKET), then cleared when the sale closes
--            (SETTLED). Taken off the sale → RELEASED (unlocked at the FDC).
--   PREPAY   the customer pays first: a prepay line on the sale (IN_BASKET);
--            when the sale closes the pump is authorised up to that amount
--            (AUTHORISED; AUTH_FAILED is retried); when the pump finishes the
--            unused prepay is refunded on the sale (SETTLED). A prepay taken
--            back before any fuel flows is CANCELLED (refunded in full).
-- Grade, gallons (thousandths, volume_milli), price per gallon (thousandths
-- of a dollar, price_mills) and the amount are what the dispenser reported.
-- fdc_cleared = 0 while the FDC still has to be told the sale is settled
-- (it was unreachable at that moment); the store retries.
--
-- check_lines.fuel_sale_id ties a fuel or prepay line to its row. Every other
-- line keeps NULL, so the pubs and the bottle shop are unchanged.

CREATE TABLE IF NOT EXISTS fuel_sales (id INTEGER PRIMARY KEY AUTOINCREMENT, pump INT NOT NULL, mode VARCHAR(8) NOT NULL, status VARCHAR(16) NOT NULL, check_id INT NULL, line_id INT NULL, prepaid_cents BIGINT NULL, fdc_trx_id VARCHAR(40) NULL, fdc_auth_id VARCHAR(40) NULL, nozzle INT NULL, grade VARCHAR(8) NULL, grade_name VARCHAR(40) NULL, volume_milli BIGINT NULL, price_mills BIGINT NULL, amount_cents BIGINT NULL, refund_cents BIGINT NULL, refund_id INT NULL, change_given INT NOT NULL DEFAULT 0, fdc_cleared INT NOT NULL DEFAULT 0, error VARCHAR(200) NULL, created_at TEXT NOT NULL, completed_at TEXT NULL, settled_at TEXT NULL);
CREATE INDEX IF NOT EXISTS fuel_sales_status ON fuel_sales (status);
CREATE INDEX IF NOT EXISTS fuel_sales_check ON fuel_sales (check_id);
CREATE INDEX IF NOT EXISTS fuel_sales_trx ON fuel_sales (fdc_trx_id);
ALTER TABLE check_lines ADD COLUMN fuel_sale_id INT NULL;
