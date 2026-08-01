-- V36: Organisations — the tenant boundary.
--
-- Until now the installation was single-tenant: parts, categories, spec definitions and tags were
-- one global pool, and the only scoping axis was per-user (location.owner_id, part.created_by_id).
-- From here on every part, category, location, spec definition, tag and project belongs to exactly
-- one organisation, and every user is a member of one or more organisations.
--
-- Two organisations are created:
--   * "Initial Organisation" — receives all existing data. Meant to be renamed by the operator.
--   * "Template"             — a copy of the taxonomy/specs/tags only (no parts, stock or
--                              locations). Creating a new organisation clones this one.
-- The template is identified by the is_template flag, not by its name, precisely because the names
-- are expected to change.

-- ---------------------------------------------------------------------------------------------
-- 1. The organisation itself and user membership.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE organisation (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    is_template BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);

-- One row per (user, organisation). A user may belong to several organisations and switches
-- between them; the current one is held in the HTTP session.
CREATE TABLE app_user_organisation (
    user_id         BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    organisation_id BIGINT NOT NULL REFERENCES organisation(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, organisation_id)
);

CREATE INDEX idx_app_user_organisation_org ON app_user_organisation(organisation_id);

-- The organisation the user last selected — the default when a new session starts. Mirrors the
-- last_location_id pattern from V22: a remembered pointer, not a managed account setting.
ALTER TABLE app_user
    ADD COLUMN last_organisation_id BIGINT REFERENCES organisation(id) ON DELETE SET NULL;

INSERT INTO organisation (name, description, is_template, created_at, updated_at) VALUES
    ('Initial Organisation',
     'All data that existed before organisations were introduced. Rename as appropriate.',
     FALSE, now(), now()),
    ('Template',
     'Blueprint organisation. Its categories, spec fields and tags are copied into every newly '
     || 'created organisation. Holds no parts, locations or stock.',
     TRUE, now(), now());

-- ---------------------------------------------------------------------------------------------
-- 2. Tag every tenant-owned table with its organisation.
--
-- stock_entry, stock_movement, part_stock_threshold, part_attachment, project_part, project_stock,
-- part_tag and category_spec deliberately get no column: they derive their organisation through
-- part_id / location_id / project_id, so there is nothing that can drift out of sync.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE part            ADD COLUMN organisation_id BIGINT;
ALTER TABLE category        ADD COLUMN organisation_id BIGINT;
ALTER TABLE location        ADD COLUMN organisation_id BIGINT;
ALTER TABLE spec_definition ADD COLUMN organisation_id BIGINT;
ALTER TABLE tag             ADD COLUMN organisation_id BIGINT;
ALTER TABLE project         ADD COLUMN organisation_id BIGINT;

UPDATE part            SET organisation_id = (SELECT id FROM organisation WHERE NOT is_template);
UPDATE category        SET organisation_id = (SELECT id FROM organisation WHERE NOT is_template);
UPDATE location        SET organisation_id = (SELECT id FROM organisation WHERE NOT is_template);
UPDATE spec_definition SET organisation_id = (SELECT id FROM organisation WHERE NOT is_template);
UPDATE tag             SET organisation_id = (SELECT id FROM organisation WHERE NOT is_template);
UPDATE project         SET organisation_id = (SELECT id FROM organisation WHERE NOT is_template);

ALTER TABLE part            ALTER COLUMN organisation_id SET NOT NULL;
ALTER TABLE category        ALTER COLUMN organisation_id SET NOT NULL;
ALTER TABLE location        ALTER COLUMN organisation_id SET NOT NULL;
ALTER TABLE spec_definition ALTER COLUMN organisation_id SET NOT NULL;
ALTER TABLE tag             ALTER COLUMN organisation_id SET NOT NULL;
ALTER TABLE project         ALTER COLUMN organisation_id SET NOT NULL;

ALTER TABLE part            ADD CONSTRAINT fk_part_organisation
    FOREIGN KEY (organisation_id) REFERENCES organisation(id);
ALTER TABLE category        ADD CONSTRAINT fk_category_organisation
    FOREIGN KEY (organisation_id) REFERENCES organisation(id);
ALTER TABLE location        ADD CONSTRAINT fk_location_organisation
    FOREIGN KEY (organisation_id) REFERENCES organisation(id);
ALTER TABLE spec_definition ADD CONSTRAINT fk_spec_definition_organisation
    FOREIGN KEY (organisation_id) REFERENCES organisation(id);
ALTER TABLE tag             ADD CONSTRAINT fk_tag_organisation
    FOREIGN KEY (organisation_id) REFERENCES organisation(id);
ALTER TABLE project         ADD CONSTRAINT fk_project_organisation
    FOREIGN KEY (organisation_id) REFERENCES organisation(id);

CREATE INDEX idx_part_organisation            ON part(organisation_id);
CREATE INDEX idx_category_organisation        ON category(organisation_id);
CREATE INDEX idx_location_organisation        ON location(organisation_id);
CREATE INDEX idx_spec_definition_organisation ON spec_definition(organisation_id);
CREATE INDEX idx_tag_organisation             ON tag(organisation_id);
CREATE INDEX idx_project_organisation         ON project(organisation_id);

-- ---------------------------------------------------------------------------------------------
-- 3. Everyone becomes a member of the Initial Organisation, and starts there.
-- ---------------------------------------------------------------------------------------------

INSERT INTO app_user_organisation (user_id, organisation_id)
SELECT a.id, o.id FROM app_user a CROSS JOIN organisation o WHERE NOT o.is_template;

UPDATE app_user SET last_organisation_id = (SELECT id FROM organisation WHERE NOT is_template);

-- ---------------------------------------------------------------------------------------------
-- 4. Uniqueness becomes per-organisation.
--
-- Two organisations must be able to catalogue the same part number, define the same spec key and
-- use the same tag name without colliding. app_user.email stays globally unique — a login is not
-- organisation-scoped. This has to happen before the clone below, which would otherwise trip the
-- old global constraints.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE part DROP CONSTRAINT part_part_number_key;
ALTER TABLE part ADD CONSTRAINT uq_part_organisation_part_number UNIQUE (organisation_id, part_number);

ALTER TABLE spec_definition DROP CONSTRAINT spec_definition_json_name_key;
ALTER TABLE spec_definition ADD CONSTRAINT uq_spec_definition_organisation_json_name
    UNIQUE (organisation_id, json_name);

-- Replaces tag_name_ci_idx from V28 (case-independent names, now scoped to the organisation).
DROP INDEX tag_name_ci_idx;
CREATE UNIQUE INDEX tag_organisation_name_ci_idx ON tag (organisation_id, LOWER(name));

-- ---------------------------------------------------------------------------------------------
-- 5. Clone the taxonomy, spec fields and tags into the Template organisation.
--
-- Ids are pre-allocated from each table's sequence into a mapping table so that self-references
-- (category.parent_id) and the category_spec link table can be remapped to the copies. The
-- INSERT ... SELECT from the same table is safe: PostgreSQL reads the table as of statement start,
-- so the freshly inserted copies are not re-read.
-- ---------------------------------------------------------------------------------------------

CREATE TEMPORARY TABLE cat_map AS
    SELECT c.id AS old_id, nextval('category_id_seq') AS new_id FROM category c;

INSERT INTO category (id, name, description, parent_id, organisation_id)
SELECT m.new_id, c.name, c.description, pm.new_id,
       (SELECT id FROM organisation WHERE is_template)
  FROM category c
  JOIN cat_map m  ON m.old_id  = c.id
  LEFT JOIN cat_map pm ON pm.old_id = c.parent_id;

CREATE TEMPORARY TABLE spec_map AS
    SELECT s.id AS old_id, nextval('spec_definition_id_seq') AS new_id FROM spec_definition s;

INSERT INTO spec_definition (id, json_name, name, data_type, unit, metric_prefix, options,
                             display_order, major_type, created_at, organisation_id)
SELECT m.new_id, s.json_name, s.name, s.data_type, s.unit, s.metric_prefix, s.options,
       s.display_order, s.major_type, s.created_at,
       (SELECT id FROM organisation WHERE is_template)
  FROM spec_definition s
  JOIN spec_map m ON m.old_id = s.id;

CREATE TEMPORARY TABLE tag_map AS
    SELECT t.id AS old_id, nextval('tag_id_seq') AS new_id FROM tag t;

INSERT INTO tag (id, name, created_at, organisation_id)
SELECT m.new_id, t.name, t.created_at, (SELECT id FROM organisation WHERE is_template)
  FROM tag t
  JOIN tag_map m ON m.old_id = t.id;

INSERT INTO category_spec (category_id, spec_id)
SELECT cm.new_id, sm.new_id
  FROM category_spec cs
  JOIN cat_map  cm ON cm.old_id = cs.category_id
  JOIN spec_map sm ON sm.old_id = cs.spec_id;

DROP TABLE cat_map;
DROP TABLE spec_map;
DROP TABLE tag_map;

-- ---------------------------------------------------------------------------------------------
-- 6. Locations stop being per-user: every member of an organisation shares its locations.
--    Sibling-name uniqueness (previously per owner) is enforced in LocationService against the
--    organisation instead.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE location DROP COLUMN owner_id;

-- ---------------------------------------------------------------------------------------------
-- 7. The new GLOBAL_ADMIN permission gates the Organisations screen. Grant it to whoever already
--    administers this installation (every holder of USERS_EDIT) so the screen is reachable
--    immediately after the upgrade.
-- ---------------------------------------------------------------------------------------------

INSERT INTO app_user_permission (user_id, permission)
SELECT user_id, 'GLOBAL_ADMIN' FROM app_user_permission WHERE permission = 'USERS_EDIT'
ON CONFLICT DO NOTHING;
