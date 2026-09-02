-- V59: Projects get two phases (ACTIVE / CANCELLED) and allocate their parts from stock at once.
--
-- Before this migration a project walked PLANNING -> BUILDING -> COMPLETED/CANCELLED and stock was
-- pulled by hand, one "Pull Stock" dialog at a time. Now a part put on the project's part list is
-- taken out of stock immediately, and project_part carries how much is allocated beside how much is
-- needed -- cancelling returns the allocation and keeps the need, reactivating takes it out again.

-- 1. How much of this part the project is actually holding. The need stays qty_per_instance (times
--    the project's instance_count); this is the absolute count currently out of stock.
ALTER TABLE project_part
    ADD COLUMN qty_allocated INT NOT NULL DEFAULT 0;

-- 2. Deleting a cancelled project is logical: stock_movement rows keep pointing at it so the ledger
--    can still say which project a PROJECT_OUT/PROJECT_RETURN belonged to. Everything else the
--    project owns (its part list, its allocations, its imported BOM) is deleted for real.
ALTER TABLE project
    ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;

-- 3. Backfill the allocation from the batches already pulled, for projects that still hold them.
UPDATE project_part pp
SET qty_allocated = COALESCE((
        SELECT SUM(ps.quantity)
        FROM project_stock ps
        WHERE ps.project_id = pp.project_id
          AND ps.part_id = pp.part_id), 0)
WHERE pp.project_id IN (SELECT id FROM project WHERE status <> 'CANCELLED');

-- 4. A cancelled project holds nothing in the new model. Its old project_stock rows were either
--    returned to stock by the old cancel dialog or deliberately written off as consumed; in both
--    cases the on-hand figures are already right and the batches are spent.
DELETE FROM project_stock
WHERE project_id IN (SELECT id FROM project WHERE status = 'CANCELLED');

-- 5. Collapse the old status ladder. Everything that was not cancelled is simply active.
UPDATE project SET status = 'ACTIVE' WHERE status <> 'CANCELLED';

ALTER TABLE project ALTER COLUMN status SET DEFAULT 'ACTIVE';
