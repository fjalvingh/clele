CREATE TABLE category (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    parent_id   BIGINT REFERENCES category(id)
);

CREATE INDEX idx_category_parent ON category(parent_id);

CREATE TABLE part (
    id             BIGSERIAL PRIMARY KEY,
    part_number    VARCHAR(255) NOT NULL UNIQUE,
    name           VARCHAR(255) NOT NULL,
    description    TEXT,
    manufacturer   VARCHAR(255),
    datasheet_url  TEXT,
    specs          JSONB,
    category_id    BIGINT REFERENCES category(id),
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_part_category ON part(category_id);
CREATE INDEX idx_part_part_number ON part(part_number);
CREATE INDEX idx_part_name ON part(name);

CREATE TABLE location (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL UNIQUE,
    description TEXT
);

CREATE TABLE stock_entry (
    id               BIGSERIAL PRIMARY KEY,
    part_id          BIGINT NOT NULL REFERENCES part(id),
    location_id      BIGINT NOT NULL REFERENCES location(id),
    quantity         INTEGER NOT NULL DEFAULT 0,
    minimum_quantity INTEGER NOT NULL DEFAULT 0,
    UNIQUE (part_id, location_id)
);

CREATE INDEX idx_stock_part ON stock_entry(part_id);
CREATE INDEX idx_stock_location ON stock_entry(location_id);
