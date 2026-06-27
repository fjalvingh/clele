-- Part stock thresholds: minimum on-hand per part at a root location (parent_id IS NULL).
-- Low stock = SUM of stock across all sub-locations of a root < minimum_quantity.
CREATE TABLE part_stock_threshold (
    id               BIGSERIAL PRIMARY KEY,
    part_id          BIGINT NOT NULL REFERENCES part(id) ON DELETE CASCADE,
    location_id      BIGINT NOT NULL REFERENCES location(id) ON DELETE CASCADE,
    minimum_quantity INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_pst_part_location UNIQUE (part_id, location_id)
);
CREATE INDEX idx_pst_part_id ON part_stock_threshold(part_id);
CREATE INDEX idx_pst_location_id ON part_stock_threshold(location_id);

-- Migrate: for each stock_entry with minimum_quantity > 0, walk up to the root location
-- and upsert a threshold row (taking MAX when multiple sub-locations have different values).
WITH RECURSIVE root_of(loc_id, root_id, parent_id) AS (
    SELECT id, id, parent_id FROM location
    UNION ALL
    SELECT r.loc_id, l.id, l.parent_id FROM location l JOIN root_of r ON l.id = r.parent_id
)
INSERT INTO part_stock_threshold (part_id, location_id, minimum_quantity)
SELECT se.part_id, r.root_id, MAX(se.minimum_quantity)
FROM stock_entry se
JOIN root_of r ON r.loc_id = se.location_id AND r.parent_id IS NULL
WHERE se.minimum_quantity > 0
GROUP BY se.part_id, r.root_id
ON CONFLICT (part_id, location_id) DO UPDATE
    SET minimum_quantity = GREATEST(part_stock_threshold.minimum_quantity, EXCLUDED.minimum_quantity);

ALTER TABLE stock_entry DROP COLUMN minimum_quantity;
