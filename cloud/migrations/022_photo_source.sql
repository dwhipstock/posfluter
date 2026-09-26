-- 022: where each item's photo came from (AI menu photos).
--
-- Item snapshots may now carry photoSource: 'original' | 'ai_generated' |
-- 'ai_enhanced'. The portal Menu page shows a small "AI" badge on the two AI
-- values (a transparency point). NULL = the store sent none (an older store,
-- or a photo from before the field): shown as an ordinary photo. Like
-- photo_version, an absent key keeps the stored value on the next upsert.

ALTER TABLE catalog_items ADD COLUMN photo_source TEXT NULL;
