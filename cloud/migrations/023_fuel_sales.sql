-- 023: fuel sales (a gas station). One row per completed fuelling the store
-- has settled (CONTRACT §2, `fuel.sale`): postpay when the sale that paid for
-- it closed, prepay when the pump finished and any unused prepay was handed
-- back. Amounts are what was dispensed, in cents, tax-inclusive (fuel taxes
-- are in the pump price). A prepay's unused change is a separate
-- refund.created event; refund_cents here only notes it and is never netted
-- out of the fuel figures again. Volumes are thousandths of a US gallon, pump
-- prices thousandths of a dollar per gallon. Idempotent upsert by
-- (tenant, venue, fuel_sale_id).
--
-- check_lines.fuel keeps a fuel line's pump details as the store sent them
-- (pump, nozzle, grade, volume, price, mode), for display; the fuel figures
-- come from fuel_sales. NULL = not a fuel line.

CREATE TABLE fuel_sales (
    tenant_id TEXT NOT NULL,
    venue_id TEXT NOT NULL,
    fuel_sale_id BIGINT NOT NULL,
    check_id INTEGER,
    pump INTEGER,
    nozzle INTEGER,
    grade TEXT,
    grade_name TEXT,
    volume_milli BIGINT,
    price_mills BIGINT,
    amount_cents BIGINT,
    mode TEXT,
    prepaid_cents BIGINT,
    refund_cents BIGINT,
    fdc_transaction_id TEXT,
    completed_at TIMESTAMPTZ,
    currency TEXT,
    PRIMARY KEY (tenant_id, venue_id, fuel_sale_id)
);
CREATE INDEX fuel_sales_completed_at ON fuel_sales (tenant_id, venue_id, completed_at);

ALTER TABLE check_lines ADD COLUMN fuel JSONB NULL;
