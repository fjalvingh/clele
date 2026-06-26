-- Track which changelog entry the user has last read.
-- Stored as 8-digit date string matching the filename (e.g. '20260623').
ALTER TABLE app_user ADD COLUMN last_read_changes VARCHAR(8);
