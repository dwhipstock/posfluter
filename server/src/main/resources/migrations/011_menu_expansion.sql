-- Add bilingual descriptions; catalog rows are supplied by CopperLanternSeed.
ALTER TABLE items ADD COLUMN description_fr VARCHAR(500) NOT NULL DEFAULT '';
ALTER TABLE items ADD COLUMN description_en VARCHAR(500) NOT NULL DEFAULT '';
