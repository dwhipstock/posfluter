-- 063: carry-out (to-go) orders in a full-service restaurant reuse the
-- numbered orders of the quick-serve counter (counter_orders, source
-- CARRY_OUT). A call-in order can carry the customer's name and phone; both
-- are optional and stay in the store (counter_orders is never synced). The
-- orders sit on the off-floor "carry-out" sale location
-- (SaleLocations.ensureCarryOut), created at startup, not here.
ALTER TABLE counter_orders ADD COLUMN customer_name VARCHAR(60) NULL;
ALTER TABLE counter_orders ADD COLUMN customer_phone VARCHAR(32) NULL;
