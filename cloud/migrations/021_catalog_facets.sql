-- 021: a big retail catalog (~5,000 products per store).
--
-- Item snapshots may now carry a producer (brand), a subcategory (style,
-- varietal or spirit type: "IPA", "Pinot Noir", "Tequila Blanco") and a short
-- size/pack label ("6-pack", "750 ml"). They are mirrored for the portal's
-- Products and Stock filters; NULL = the store sent none (the pubs, older
-- stores). The portal filters a store's products in the API after one indexed
-- read of that store's rows (primary key: tenant, venue, id), so no text index
-- is needed at this size. Variants are read per store, grouped by item.

ALTER TABLE catalog_items ADD COLUMN brand TEXT NULL;
ALTER TABLE catalog_items ADD COLUMN subcategory TEXT NULL;
ALTER TABLE catalog_items ADD COLUMN size_label TEXT NULL;
CREATE INDEX IF NOT EXISTS catalog_variants_item_idx ON catalog_variants (tenant_id, venue_id, item_id);
