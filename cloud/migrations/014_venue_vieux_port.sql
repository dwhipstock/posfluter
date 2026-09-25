-- 014: the demo tenant's original store, venue id 'main', becomes 'vieux-port'
-- (Copper Lantern — Vieux-Port) now that the tenant has a second store.
--
-- Venue ids are plain TEXT columns (no foreign keys), so every table that carries
-- a venue_id is rewritten in the same transaction: history, projections, keys,
-- devices, pairing codes and the venue row itself move together and nothing is
-- orphaned. The store tablet never sends a venue id (its API key resolves to the
-- venue server-side), so it keeps syncing unchanged.
--
-- Guarded: runs only when 'main' exists and 'vieux-port' does not; otherwise it
-- is a no-op (fresh databases, or an operator who already created 'vieux-port').
-- Only tenant 'copperlantern' is touched.
-- (One statement: the runner splits on a ';' at end of line, so the block keeps
-- its inner semicolons mid-line.)

DO $mig$ DECLARE t text; BEGIN
  IF EXISTS (SELECT 1 FROM venues WHERE tenant_id = 'copperlantern' AND id = 'main')
     AND NOT EXISTS (SELECT 1 FROM venues WHERE tenant_id = 'copperlantern' AND id = 'vieux-port') THEN
    FOR t IN SELECT c.table_name FROM information_schema.columns c
               JOIN information_schema.tables tb ON tb.table_schema = c.table_schema AND tb.table_name = c.table_name
              WHERE c.table_schema = current_schema() AND c.column_name = 'venue_id' AND tb.table_type = 'BASE TABLE'
    LOOP
      EXECUTE format('UPDATE %I SET venue_id = %L WHERE tenant_id = %L AND venue_id = %L', t, 'vieux-port', 'copperlantern', 'main'); END LOOP; UPDATE venues SET id = 'vieux-port' WHERE tenant_id = 'copperlantern' AND id = 'main'; END IF; END $mig$;
