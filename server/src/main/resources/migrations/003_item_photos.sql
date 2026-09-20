-- 003: per-item photo. The file lives in the PhotoStore (filesystem for M1,
-- data/photos/{itemId}.jpg|.png); this column records the stored path.

ALTER TABLE items ADD COLUMN photo_path VARCHAR(300) NULL;
