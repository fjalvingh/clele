-- The label media is now read from the printer over IPP by the daemon and reported on every poll,
-- rather than configured by hand. A manual tape width could not describe die-cut labels (which
-- also have a fixed length) and went stale whenever the roll was changed.
ALTER TABLE print_daemon DROP COLUMN tape_width_mm;

ALTER TABLE print_daemon ADD COLUMN media_kind VARCHAR(20);
ALTER TABLE print_daemon ADD COLUMN media_width_mm INTEGER;
ALTER TABLE print_daemon ADD COLUMN media_length_mm INTEGER;
ALTER TABLE print_daemon ADD COLUMN media_name VARCHAR(128);
