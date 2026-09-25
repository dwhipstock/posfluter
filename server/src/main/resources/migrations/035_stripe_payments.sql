-- 035: optional Stripe card payments (test mode, Terminal simulated reader).
-- A Stripe tender is an ordinary tenders row with type 'STRIPE' plus the
-- PaymentIntent it settled; a refund of it records the PaymentIntent and the
-- Stripe refund id, so a refund is only ever written after Stripe made it.
-- stripe_payments tracks each PaymentIntent the store asked Stripe for (one per
-- attempt) so confirm/cancel are idempotent and an interrupted payment can be
-- reconciled. public_id is what the client holds. No key, card data or client
-- secret is stored.
ALTER TABLE tenders ADD COLUMN stripe_payment_intent_id VARCHAR(64) NULL;
ALTER TABLE refunds ADD COLUMN stripe_payment_intent_id VARCHAR(64) NULL;
ALTER TABLE refunds ADD COLUMN stripe_refund_id VARCHAR(64) NULL;
CREATE TABLE IF NOT EXISTS stripe_payments (id INTEGER PRIMARY KEY AUTOINCREMENT, public_id VARCHAR(40) NOT NULL, check_id INT NOT NULL, bill_group_id INT NULL, amount_cents BIGINT NOT NULL, currency VARCHAR(3) NOT NULL, payment_intent_id VARCHAR(64) NULL, status VARCHAR(20) NOT NULL, tender_id INT NULL, last_error VARCHAR(300) NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL);
CREATE UNIQUE INDEX IF NOT EXISTS stripe_payments_public_id ON stripe_payments (public_id);
CREATE INDEX IF NOT EXISTS stripe_payments_check_id ON stripe_payments (check_id);
CREATE INDEX IF NOT EXISTS stripe_payments_pi ON stripe_payments (payment_intent_id);
