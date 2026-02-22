CREATE TABLE spec_definition (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(100) NOT NULL UNIQUE,
    data_type     VARCHAR(20)  NOT NULL DEFAULT 'TEXT',  -- TEXT | NUMBER | BOOLEAN | SELECT
    unit          VARCHAR(20),                            -- NUMBER only
    options       TEXT,                                   -- SELECT only, stored as JSON array string
    display_order INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE category_spec (
    category_id BIGINT NOT NULL REFERENCES category(id) ON DELETE CASCADE,
    spec_id     BIGINT NOT NULL REFERENCES spec_definition(id) ON DELETE CASCADE,
    PRIMARY KEY (category_id, spec_id)
);
