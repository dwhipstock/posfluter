-- 051: AI menu setup history. Every Apply of an AI proposal is one change set
-- (who applied it, which manager approved it, when, from photos or chat); each
-- row is one menu thing it touched with its state before and after (JSON).
-- "Revert" restores the before states through the ordinary menu code and
-- stamps reverted_at. Local to the store: never synced (the menu edits
-- themselves sync as usual through the outbox).
CREATE TABLE IF NOT EXISTS menu_change_sets (id VARCHAR(40) PRIMARY KEY, created_at TEXT NOT NULL, user_id VARCHAR(64) NOT NULL, approver_id VARCHAR(64) NOT NULL, source VARCHAR(16) NOT NULL, summary VARCHAR(500) NOT NULL DEFAULT '', reverted_at TEXT NULL, reverted_by VARCHAR(64) NULL);
CREATE TABLE IF NOT EXISTS menu_change_rows (id INTEGER PRIMARY KEY AUTOINCREMENT, set_id VARCHAR(40) NOT NULL, seq INT NOT NULL, entity VARCHAR(16) NOT NULL, entity_id VARCHAR(160) NOT NULL, action VARCHAR(16) NOT NULL, title VARCHAR(200) NOT NULL DEFAULT '', before_json TEXT NULL, after_json TEXT NULL);
CREATE INDEX IF NOT EXISTS menu_change_rows_set ON menu_change_rows (set_id, seq);
CREATE INDEX IF NOT EXISTS menu_change_sets_created ON menu_change_sets (created_at);
