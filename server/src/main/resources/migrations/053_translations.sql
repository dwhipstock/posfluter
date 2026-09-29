-- Names beyond the two catalog slots (name_fr / name_en): one row per thing
-- and extra language, e.g. ('item', 'poutine', 'de', 'Klassische Poutine').
-- entity: item | category | zone | floor_object. A language with no row here
-- reads the English name, then the French one. Local to the store (not synced).
CREATE TABLE IF NOT EXISTS translations (entity VARCHAR(24) NOT NULL, entity_id VARCHAR(160) NOT NULL, lang VARCHAR(8) NOT NULL, text VARCHAR(500) NOT NULL, PRIMARY KEY (entity, entity_id, lang));
