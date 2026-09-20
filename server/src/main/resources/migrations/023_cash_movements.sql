-- 023: non-sale cash movements — money into or out of the till that isn't a
-- sale, a refund, or the opening float. IN = float top-up / change added;
-- OUT = petty cash, a supplier paid in cash, an owner draw. Free-text reason
-- (v1; a reason-code master is out of scope). Manager-gated, posted to the
-- current shift, and folded into the shift's expected-cash reconciliation:
-- expected = opening float + cash sales + cash-in - cash-out - cash refunds.

CREATE TABLE IF NOT EXISTS cash_movements (id INTEGER PRIMARY KEY AUTOINCREMENT, shift_id INT NULL, direction VARCHAR(4) NOT NULL, amount_cents BIGINT NOT NULL, reason VARCHAR(300) NOT NULL, created_by VARCHAR(64) NOT NULL, created_at TEXT NOT NULL, CONSTRAINT chk_cash_movements_signed_integer_id CHECK (id BETWEEN -2147483648 AND 2147483647), CONSTRAINT chk_cash_movements_signed_integer_shift_id CHECK (shift_id BETWEEN -2147483648 AND 2147483647));
CREATE INDEX IF NOT EXISTS cash_movements_shift_id ON cash_movements (shift_id);
