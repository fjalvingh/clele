-- Track which user created each part.
--
-- Parts can be added by any user with PARTS_EDIT. Recording the creator lets an admin
-- remove one user's contributions (e.g. a bad bulk import) without dropping the whole
-- catalogue. Mirrors the per-user ownership added for locations in V12.

-- 1. New column (nullable for now so existing rows can be backfilled).
ALTER TABLE part ADD COLUMN created_by_id BIGINT REFERENCES app_user(id);

-- 2. Existing parts predate ownership — attribute them to the bootstrap admin.
UPDATE part
   SET created_by_id = (SELECT id FROM app_user WHERE email = 'admin@clele.local')
 WHERE created_by_id IS NULL;

-- 3. Enforce ownership going forward.
ALTER TABLE part ALTER COLUMN created_by_id SET NOT NULL;
CREATE INDEX idx_part_created_by ON part(created_by_id);
