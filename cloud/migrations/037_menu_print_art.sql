-- 037: printable menus' AI artwork cache (API.md "Printable menus"). One row
-- per picture the image service made for a printed menu: a header, a section
-- illustration, a flyer background or a stand-in dish photo. [key] is a
-- hash of what the picture was asked for (the style, its purpose, the
-- subject, the manager's notes), so a re-generate with new wording reuses
-- the same art instead of paying for it again; "New artwork" overwrites it.
-- Only pictures are kept here: never a prompt, notes text or key.
CREATE TABLE IF NOT EXISTS menu_print_art (
    tenant_id    TEXT        NOT NULL,
    key          TEXT        NOT NULL,
    purpose      TEXT        NOT NULL,
    content      BYTEA       NOT NULL,
    content_type TEXT        NOT NULL,
    provider     TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, key)
);
CREATE INDEX IF NOT EXISTS menu_print_art_created ON menu_print_art (created_at);
