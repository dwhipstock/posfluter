-- More floor objects: ENTRANCE, HOST_STAND, KITCHEN, RESTROOMS, STAGE, and
-- CUSTOM ones a manager makes by hand or from a photo ("Add from photo"). The
-- type column is TEXT already, so nothing to widen. A CUSTOM object carries
-- its caption in label_fr / label_en plus:
--   icon   a key from the fixed icon list the tablet ships (never an image)
--   shape  RECT | ROUND
-- Both stay NULL on the built-in types, whose type drives the look.
ALTER TABLE floor_objects ADD COLUMN icon TEXT NULL;
ALTER TABLE floor_objects ADD COLUMN shape TEXT NULL;
