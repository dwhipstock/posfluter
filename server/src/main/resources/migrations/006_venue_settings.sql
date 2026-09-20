-- 006: owner-tunable venue settings — the DATA half of the data-vs-policy
-- split. Policy choices (tax mode, rounding, auth) stay typed in code; these
-- are values the owner edits at runtime without a redeploy. Single venue →
-- single row (id=1). Seeded with the current CopperLanternConfig placeholders.

CREATE TABLE IF NOT EXISTS venue_settings (
    id INT NOT NULL PRIMARY KEY,
    card_processor VARCHAR(100) NOT NULL,
    bank_name VARCHAR(100) NOT NULL,
    bank_account_number VARCHAR(30) NOT NULL,
    bank_account_name VARCHAR(100) NOT NULL,
    service_charge_percent INT DEFAULT 0 NOT NULL,
    corkage_per_bottle_cents BIGINT DEFAULT 0 NOT NULL,
    receipt_footer VARCHAR(300) NOT NULL,
    venue_phone VARCHAR(30) NOT NULL,
    venue_address VARCHAR(200) NOT NULL
);

INSERT OR IGNORE INTO venue_settings (id, card_processor, bank_name, bank_account_number, bank_account_name, service_charge_percent, corkage_per_bottle_cents, receipt_footer, venue_phone, venue_address) VALUES
 (1, 'Manual card terminal', 'Example Credit Union', '0001234567', 'The Copper Lantern Pub', 0, 2500, 'Thank you for visiting!', '+1 416 555 0142', '47 Lantern Lane, Toronto, ON');
