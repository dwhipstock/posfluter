-- 048: card-present payments through an integrated terminal other than Stripe
-- (the built-in simulator, J.P. Morgan). A TERMINAL tender is an ordinary
-- tenders row plus the terminal's payment id; a refund of it records the
-- terminal's payment and refund ids, so a refund is only ever written after
-- the terminal made it.
-- card_json (tenders): what the receipt prints about the card — brand, last 4
-- digits, entry mode, auth code and the EMV AID/TVR/TSI. Never a full card
-- number. Also filled for STRIPE tenders when Stripe returns the charge.
-- terminal_payments tracks each attempt (one per "Charge card" press) so a
-- repeat poll or cancel is idempotent and an interrupted payment can be
-- reconciled. public_id is what the client holds.
ALTER TABLE tenders ADD COLUMN terminal_payment_ref VARCHAR(64) NULL;
ALTER TABLE tenders ADD COLUMN card_json TEXT NULL;
ALTER TABLE refunds ADD COLUMN terminal_payment_ref VARCHAR(64) NULL;
ALTER TABLE refunds ADD COLUMN terminal_refund_ref VARCHAR(64) NULL;
CREATE TABLE IF NOT EXISTS terminal_payments (id INTEGER PRIMARY KEY AUTOINCREMENT, public_id VARCHAR(40) NOT NULL, provider VARCHAR(20) NOT NULL, check_id INT NOT NULL, bill_group_id INT NULL, amount_cents BIGINT NOT NULL, tip_cents BIGINT NOT NULL DEFAULT 0, currency VARCHAR(3) NOT NULL, terminal_ref VARCHAR(64) NULL, status VARCHAR(20) NOT NULL, prompt VARCHAR(40) NULL, card_json TEXT NULL, decline_code VARCHAR(40) NULL, error_code VARCHAR(40) NULL, tender_id INT NULL, last_error VARCHAR(300) NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL);
CREATE UNIQUE INDEX IF NOT EXISTS terminal_payments_public_id ON terminal_payments (public_id);
CREATE INDEX IF NOT EXISTS terminal_payments_check_id ON terminal_payments (check_id);
CREATE INDEX IF NOT EXISTS terminal_payments_ref ON terminal_payments (terminal_ref);
CREATE INDEX IF NOT EXISTS tenders_terminal_payment_ref ON tenders (terminal_payment_ref);
