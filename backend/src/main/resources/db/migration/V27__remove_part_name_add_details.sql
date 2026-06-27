-- Drop the redundant name column (was always the same as part_number).
-- Add details for long-form text notes per part.
ALTER TABLE part DROP COLUMN name;
ALTER TABLE part ADD COLUMN details TEXT;
