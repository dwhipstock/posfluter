-- 054: the manager's log of AI calls (menu chat, menu from photos, translate,
-- room object from photo): who, which device, when, what kind, the outcome
-- (proposed / off_topic / no_change / rate_limited / an error code) and how
-- many changes. Never keys, photos or prompts. Local to the store (not synced).
CREATE TABLE IF NOT EXISTS ai_requests (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at TEXT NOT NULL, user_id VARCHAR(64) NOT NULL, approver_id VARCHAR(64) NOT NULL, device_id VARCHAR(64) NULL, kind VARCHAR(16) NOT NULL, outcome VARCHAR(40) NOT NULL, changes INT NOT NULL DEFAULT 0, rejected INT NOT NULL DEFAULT 0, elapsed_ms INTEGER NOT NULL DEFAULT 0);
CREATE INDEX IF NOT EXISTS ai_requests_created ON ai_requests (created_at);
