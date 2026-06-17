-- Partsbox import: extra part metadata columns + stock movement ledger

ALTER TABLE part
    ADD COLUMN footprint   VARCHAR(64),
    ADD COLUMN mpn         VARCHAR(128),
    ADD COLUMN octopart_id VARCHAR(64);

-- Ledger of individual stock movements (purchases positive, usage negative).
-- On-hand per part+location is the sum of these; stock_entry caches that aggregate.
CREATE TABLE stock_movement (
    id          BIGSERIAL PRIMARY KEY,
    part_id     BIGINT NOT NULL REFERENCES part(id) ON DELETE CASCADE,
    location_id BIGINT NOT NULL REFERENCES location(id),
    quantity    INTEGER NOT NULL,
    unit_price  DECIMAL(10, 2),
    currency    VARCHAR(8),
    comments    TEXT,
    moved_at    TIMESTAMP NOT NULL,
    created_by  VARCHAR(64)
);

CREATE INDEX idx_stock_movement_part ON stock_movement(part_id);
CREATE INDEX idx_stock_movement_location ON stock_movement(location_id);
