-- Locations become hierarchical: a location may nest under a parent location
-- (e.g. Building A > Room B > Cupboard C). Parts can be stored at any level.
-- The parent is a self-FK; a NULL parent is a root location. ON DELETE is left
-- to the application (it refuses to delete a location that still has children).
ALTER TABLE location ADD COLUMN parent_id BIGINT REFERENCES location(id);

CREATE INDEX idx_location_parent_id ON location(parent_id);
