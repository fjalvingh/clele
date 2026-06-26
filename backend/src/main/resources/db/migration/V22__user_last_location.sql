-- Replace the per-user "default location" with a remembered "last used location".
-- The pointer is no longer an explicitly-managed account setting; it is updated automatically
-- whenever the user adds stock (Quick Add or adding stock to a part), and is used only to
-- pre-select the location on the next add.

-- 1. Rename the column.
ALTER TABLE app_user RENAME COLUMN default_location_id TO last_location_id;

-- 2. Recreate the FK so deleting a location simply clears it from any user that last used it.
--    (Previously the app forbade deleting/reassigning a user's default location.)
ALTER TABLE app_user DROP CONSTRAINT app_user_default_location_id_fkey;
ALTER TABLE app_user ADD CONSTRAINT app_user_last_location_id_fkey
    FOREIGN KEY (last_location_id) REFERENCES location(id) ON DELETE SET NULL;

-- 3. Drop the global per-owner name uniqueness left over from before locations became
--    hierarchical (V21): the same name may now legitimately repeat under different parents
--    (e.g. two "Cupboard C"s in different rooms), disambiguated by the breadcrumb. Sibling-name
--    uniqueness (same owner + same parent) is enforced in LocationService.existsSibling.
ALTER TABLE location DROP CONSTRAINT location_owner_name_key;
