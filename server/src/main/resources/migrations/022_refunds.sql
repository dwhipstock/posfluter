-- 022: refunds against a finalized (CLOSED) check. Distinct from a void, which
-- cancels a check before any money is applied. A refund returns money on a check
-- that already closed — full or partial (by line or by amount). The store
-- decomposes the reversed included tax proportionally against the check's locked
-- totals (a full refund reverses tax exactly); the cloud only aggregates.
-- gross = money returned, tax = included tax within it, net = gross - tax.
-- tender_type is how the money went back (CASH | CARD | BANK_TRANSFER; no
-- card refunds). lines_json is [{lineId,qty,amountCents}] on a by-line refund,
-- NULL on a by-amount one. shift_id posts it to the current shift (drawer math).

CREATE TABLE IF NOT EXISTS refunds (id INTEGER PRIMARY KEY AUTOINCREMENT, check_id INT NOT NULL, shift_id INT NULL, gross_cents BIGINT NOT NULL, net_cents BIGINT NOT NULL, tax_cents BIGINT NOT NULL, tender_type VARCHAR(20) NOT NULL, reason VARCHAR(300) NOT NULL, lines_json TEXT NULL, refunded_by VARCHAR(64) NOT NULL, created_at TEXT NOT NULL, CONSTRAINT fk_refunds_check_id__id FOREIGN KEY (check_id) REFERENCES checks(id) ON DELETE RESTRICT ON UPDATE RESTRICT, CONSTRAINT chk_refunds_signed_integer_id CHECK (id BETWEEN -2147483648 AND 2147483647), CONSTRAINT chk_refunds_signed_integer_check_id CHECK (check_id BETWEEN -2147483648 AND 2147483647), CONSTRAINT chk_refunds_signed_integer_shift_id CHECK (shift_id BETWEEN -2147483648 AND 2147483647));
CREATE INDEX IF NOT EXISTS refunds_check_id ON refunds (check_id);
CREATE INDEX IF NOT EXISTS refunds_shift_id ON refunds (shift_id);
