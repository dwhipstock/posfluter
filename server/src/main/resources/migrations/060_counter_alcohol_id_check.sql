-- 060: at a quick-serve counter (Copper Lantern Express) alcohol is paid only
-- after an ID check, kiosk orders included: beer and wine become
-- age-restricted, so the store's ID gate (038, AgeGate) holds the payment until
-- a passing check is recorded. A kiosk's "ID check at the counter" badge alone
-- never stopped anyone.
--
-- Only a counter store is touched: the one with the counter zone
-- (QuickServeService.ensureCounter). A pub's table checks are never gated.
-- Idempotent: running it again changes nothing. New Express stores get the
-- flag from their seed; the triggers keep an item added or changed later
-- (the menu editor, the portal's two-way menu sync) in step with its alcohol
-- flag. NOTE for merges: renumber this file if another 060 lands on main first.
UPDATE items SET age_restricted = 1 WHERE is_alcohol = 1 AND age_restricted = 0 AND EXISTS (SELECT 1 FROM zones WHERE id = 'counter');
CREATE TRIGGER IF NOT EXISTS items_counter_alcohol_insert AFTER INSERT ON items WHEN NEW.is_alcohol = 1 AND EXISTS (SELECT 1 FROM zones WHERE id = 'counter') BEGIN UPDATE items SET age_restricted = 1 WHERE id = NEW.id; END;
CREATE TRIGGER IF NOT EXISTS items_counter_alcohol_update AFTER UPDATE OF is_alcohol ON items WHEN EXISTS (SELECT 1 FROM zones WHERE id = 'counter') BEGIN UPDATE items SET age_restricted = NEW.is_alcohol WHERE id = NEW.id; END;
