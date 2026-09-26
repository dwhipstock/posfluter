-- Floor-plan captions become bilingual like zone names: label is the English
-- side (label_en) and label_fr is new. A caption a manager set before this
-- reads the same in both languages, as it did.
--
-- The demo plan's seeded captions get their French text, and the Montréal
-- receipt addresses become French-style ones, each only while it still holds
-- the old seeded value, so a caption or address an owner edited is never
-- overwritten (the pattern of 042). A new database is seeded with the new
-- values directly (CopperLanternSeed, CopperLanternVenue). The streets are
-- fictional; the postal codes use Q and U, letters Canada Post never assigns.
ALTER TABLE floor_objects RENAME COLUMN label TO label_en;
ALTER TABLE floor_objects ADD COLUMN label_fr TEXT NULL;
UPDATE floor_objects SET label_fr = label_en;

UPDATE floor_objects SET label_fr = 'Bar en cuivre' WHERE id = 'upper-bar' AND label_en = 'Copper Bar';
UPDATE floor_objects SET label_fr = 'Billard' WHERE id = 'lower-pool' AND label_en = 'Pool';
UPDATE floor_objects SET label_fr = 'Comptoir à sushis' WHERE id = 'sushi-counter' AND label_en = 'Sushi Counter';

UPDATE venue_settings SET venue_address = '47, rue de la Lanterne, Montréal (Québec) H2Y 1Q7'
 WHERE id = 1 AND venue_address = '47 Lantern Lane, Montréal, QC';
UPDATE venue_settings SET venue_address = '212, avenue du Lampion, Montréal (Québec) H2J 3U4'
 WHERE id = 1 AND venue_address = '212 Lantern Row, Montréal, QC';
