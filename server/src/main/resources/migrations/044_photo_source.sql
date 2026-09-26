-- Where an item's photo came from (AI menu photos): 'original' (a manager's
-- own upload), 'ai_generated' (made from the item's text in the house style)
-- or 'ai_enhanced' (a real photo of the dish, improved by an image model).
-- NULL = a photo from before this column, read as 'original'. The menu editor
-- and the portal show an "AI" badge on the two AI values.
ALTER TABLE items ADD COLUMN photo_source TEXT NULL;
