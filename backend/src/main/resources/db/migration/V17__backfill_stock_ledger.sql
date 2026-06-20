-- Make the stock ledger authoritative: stock_movement gains a movement type, and every
-- stock_entry whose on-hand quantity has drifted from its ledger sum gets one reconciling
-- movement so that, going forward, stock_entry.quantity == SUM(stock_movement.quantity)
-- per (part, location). See StockMovementService.apply (the funnel) for how it stays true.

ALTER TABLE stock_movement ADD COLUMN type VARCHAR(16);

-- All pre-existing movements were written by the Partsbox importer.
UPDATE stock_movement SET type = 'IMPORT' WHERE type IS NULL;

-- One reconciling movement per drifted aggregate (typically manual edits made before the
-- funnel existed). INITIAL when the entry had no ledger at all, ADJUST when it had some.
INSERT INTO stock_movement (part_id, location_id, quantity, type, created_by, comments, moved_at)
SELECT se.part_id,
       se.location_id,
       se.quantity - COALESCE(sm.total, 0),
       CASE WHEN COALESCE(sm.total, 0) = 0 THEN 'INITIAL' ELSE 'ADJUST' END,
       'system',
       'Ledger backfill',
       now()
FROM stock_entry se
LEFT JOIN (
    SELECT part_id, location_id, SUM(quantity) AS total
    FROM stock_movement
    GROUP BY part_id, location_id
) sm ON sm.part_id = se.part_id AND sm.location_id = se.location_id
WHERE se.quantity <> COALESCE(sm.total, 0);
