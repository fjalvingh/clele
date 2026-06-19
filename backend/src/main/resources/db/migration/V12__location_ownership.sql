-- Per-user location ownership and a mandatory default location per user.
--
-- Locations now belong to a user (owner). Stock at a location therefore belongs to the
-- location's owner. Every user has exactly one default location.

-- 1. New columns (nullable for now to allow backfill and to break the user<->location cycle).
ALTER TABLE location ADD COLUMN owner_id BIGINT REFERENCES app_user(id);
ALTER TABLE app_user ADD COLUMN default_location_id BIGINT REFERENCES location(id);

-- 2. Assign every existing location to the bootstrap admin.
UPDATE location
   SET owner_id = (SELECT id FROM app_user WHERE email = 'admin@clele.local')
 WHERE owner_id IS NULL;

-- 3. Guarantee the invariant: every user must own at least one location. Create a default
--    one (name includes the email to satisfy the per-owner uniqueness added below) for any
--    user that owns none.
INSERT INTO location (name, description, owner_id)
SELECT 'Default (' || a.email || ')', 'Default location', a.id
  FROM app_user a
 WHERE NOT EXISTS (SELECT 1 FROM location l WHERE l.owner_id = a.id);

-- 4. Point each user's default at one of the locations they own.
UPDATE app_user a
   SET default_location_id = (SELECT MIN(id) FROM location WHERE owner_id = a.id)
 WHERE a.default_location_id IS NULL;

-- 5. Enforce ownership going forward.
ALTER TABLE location ALTER COLUMN owner_id SET NOT NULL;
CREATE INDEX idx_location_owner ON location(owner_id);

-- 6. Names are unique per owner instead of globally (two users may both have "Bench").
ALTER TABLE location DROP CONSTRAINT location_name_key;
ALTER TABLE location ADD CONSTRAINT location_owner_name_key UNIQUE (owner_id, name);
