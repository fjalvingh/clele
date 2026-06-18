-- Separate the JSON key (json_name) from the display title (name) in spec_definition.
-- The existing records were keyed only by their human title, which does not match the
-- machine keys actually stored in part.specs, so they are dropped and re-derived by the
-- "Rescan from parts" function (POST /api/spec-definitions/rescan).

-- Remove current (mismatched) records and their category associations.
DELETE FROM category_spec;
DELETE FROM spec_definition;

-- The display title is no longer unique (two json keys may humanize to the same title).
ALTER TABLE spec_definition DROP CONSTRAINT IF EXISTS spec_definition_name_key;

-- Add the machine key. Safe as NOT NULL UNIQUE because the table is now empty.
ALTER TABLE spec_definition ADD COLUMN json_name VARCHAR(100) NOT NULL UNIQUE;
