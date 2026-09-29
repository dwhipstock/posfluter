-- 026: a store's catalog names beyond name_fr / name_en (the tablet's
-- `translations` table: es, de, ... for items, variants, categories and zones),
-- mirrored one-way for display. A snapshot carrying `names` REPLACES every
-- row of that (entity, entity_id); one without the key (an older store)
-- leaves them. The portal falls back to English, then French.
--
-- entity  item | variant | category | zone
-- lang    the language code as the store sent it (es, de, ...)

CREATE TABLE IF NOT EXISTS catalog_names (
    tenant_id  TEXT NOT NULL,
    venue_id   TEXT NOT NULL,
    entity     TEXT NOT NULL CHECK (entity IN ('item', 'variant', 'category', 'zone')),
    entity_id  TEXT NOT NULL,
    lang       TEXT NOT NULL,
    text       TEXT NOT NULL,
    PRIMARY KEY (tenant_id, venue_id, entity, entity_id, lang)
);
