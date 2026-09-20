-- Bilingual English/French pub menu categories.
CREATE TABLE IF NOT EXISTS categories (id VARCHAR(64) NOT NULL PRIMARY KEY, sort_order INT DEFAULT 0 NOT NULL, name_fr VARCHAR(100) NOT NULL, name_en VARCHAR(100) NOT NULL);
INSERT OR IGNORE INTO categories (id, sort_order, name_fr, name_en) VALUES
 ('draft-beer',0,'Bières en fût','Draft Beer'), ('bottles-cans',1,'Bouteilles et canettes','Bottles & Cans'),
 ('craft-beer',2,'Bières artisanales','Local Craft'), ('imported-beer',3,'Bières importées','Imported Beer'),
 ('cider-na',4,'Cidres et sans alcool','Cider & Non-Alcoholic'), ('red-wine',5,'Vins rouges','Red Wine'),
 ('white-wine',6,'Vins blancs','White Wine'), ('rose-sparkling',7,'Rosé, mousseux et dessert','Rosé, Sparkling & Dessert'),
 ('cocktails',8,'Cocktails','Cocktails'), ('appetizers',9,'Entrées','Appetizers'),
 ('burgers-sandwiches',10,'Burgers et sandwichs','Burgers & Sandwiches'), ('mains',11,'Plats principaux','Mains'),
 ('salads-vegetarian',12,'Salades et végétarien','Salads & Vegetarian'), ('desserts',13,'Desserts','Desserts'),
 ('late-night',14,'Menu de fin de soirée','Late Night');
ALTER TABLE items RENAME COLUMN category TO category_id;
