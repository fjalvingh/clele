-- V25: Collapse two-leg MOVE stock_movement pairs into a single atomic record.
-- The source leg keeps its location_id and negative quantity; a new target_location_id
-- column records where the stock went. The matching positive destination leg is deleted.

ALTER TABLE stock_movement
    ADD COLUMN target_location_id BIGINT REFERENCES location(id);

CREATE INDEX idx_stock_movement_target_location ON stock_movement(target_location_id);

-- Collapse existing pairs: match each negative MOVE to the nearest positive MOVE
-- for the same part with the same absolute quantity, created within 5 seconds.
DO $$
DECLARE
    pair RECORD;
BEGIN
    FOR pair IN
        SELECT DISTINCT ON (n.id)
            n.id   AS neg_id,
            p.id   AS pos_id,
            p.location_id AS dest_location_id
        FROM stock_movement n
        JOIN stock_movement p
          ON p.part_id  = n.part_id
         AND p.type     = 'MOVE'
         AND p.quantity = ABS(n.quantity)
         AND p.target_location_id IS NULL
         AND ABS(EXTRACT(EPOCH FROM (p.moved_at - n.moved_at))) < 5
        WHERE n.type     = 'MOVE'
          AND n.quantity < 0
          AND n.target_location_id IS NULL
        ORDER BY n.id, ABS(EXTRACT(EPOCH FROM (p.moved_at - n.moved_at)))
    LOOP
        UPDATE stock_movement
           SET target_location_id = pair.dest_location_id
         WHERE id = pair.neg_id;

        DELETE FROM stock_movement WHERE id = pair.pos_id;
    END LOOP;
END;
$$;
