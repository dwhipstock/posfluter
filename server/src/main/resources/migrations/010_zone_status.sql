-- 010: zones can be temporarily closed (rain on the outdoor section, a section
-- left unstaffed, the dining room being reset). OPEN | CLOSED. Existing rows
-- default to OPEN via the column default — no back-fill needed. Closing a zone
-- blocks NEW checks on its tables; open checks stay editable and tenderable.

ALTER TABLE zones ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'OPEN';
