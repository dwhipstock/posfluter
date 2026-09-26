-- 017: stores in more than one country. Each store (venue) has its own
-- country, currency and kind (restaurant | retail), set by the operator
-- (STORE_CURRENCIES / STORE_COUNTRIES / RETAIL_STORES); every existing store is
-- a Canadian restaurant in CAD, which the defaults keep.
--
-- The tenant has a reporting currency (REPORTING_CURRENCY, default CAD): the
-- portal's "All stores" view shows exact totals per currency and, next to
-- them, an approximate total converted into this currency at a fixed rate
-- from config (FX_USD_CAD=1.37). Money is never added across currencies.
--
-- Money rows carry the currency the store sent with them (CONTRACT §2). NULL =
-- an older store that sent none: it is read as its venue's currency, which for
-- every such store is CAD.
-- refunds.taxes keeps a refund's whole tax breakdown (any tax code, like
-- checks.taxes), so a tax that is neither GST nor QST nets out correctly.

ALTER TABLE venues ADD COLUMN currency TEXT NOT NULL DEFAULT 'CAD';
ALTER TABLE venues ADD COLUMN country TEXT NOT NULL DEFAULT 'CA';
ALTER TABLE venues ADD COLUMN kind TEXT NOT NULL DEFAULT 'restaurant';
ALTER TABLE tenants ADD COLUMN reporting_currency TEXT NOT NULL DEFAULT 'CAD';
ALTER TABLE checks ADD COLUMN currency TEXT NULL;
ALTER TABLE refunds ADD COLUMN currency TEXT NULL;
ALTER TABLE refunds ADD COLUMN taxes JSONB NULL;
ALTER TABLE cash_movements ADD COLUMN currency TEXT NULL;
ALTER TABLE shifts ADD COLUMN currency TEXT NULL;
