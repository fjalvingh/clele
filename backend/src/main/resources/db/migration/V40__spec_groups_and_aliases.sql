-- Spec groups replace the fixed major_type buckets, and spec aliases record the alternate JSON
-- names a specification is known by at its various sources (so merged duplicates keep matching).
--
-- Every organisation gets the three former MAJOR_TYPE values as its first groups; each spec
-- definition moves into the group matching its major_type, after which major_type is dropped.

CREATE TABLE spec_group (
    id              BIGSERIAL PRIMARY KEY,
    organisation_id BIGINT       NOT NULL REFERENCES organisation (id),
    name            VARCHAR(100) NOT NULL,
    description     TEXT,
    display_order   INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT spec_group_org_name_key UNIQUE (organisation_id, name)
);

CREATE INDEX idx_spec_group_organisation ON spec_group (organisation_id);

-- The MAJOR_TYPE values become the first groups, for every organisation.
INSERT INTO spec_group (organisation_id, name, display_order)
SELECT o.id, g.name, g.display_order
FROM organisation o
         CROSS JOIN (VALUES ('Dimensions', 0), ('Technical', 1), ('Physical', 2))
    AS g (name, display_order);

ALTER TABLE spec_definition
    ADD COLUMN group_id BIGINT REFERENCES spec_group (id);

UPDATE spec_definition sd
SET group_id = sg.id
FROM spec_group sg
WHERE sg.organisation_id = sd.organisation_id
  AND sg.name = CASE sd.major_type
                    WHEN 'DIMENSIONS' THEN 'Dimensions'
                    WHEN 'PHYSICAL' THEN 'Physical'
                    ELSE 'Technical'
                END;

-- Any spec whose organisation somehow has no group (defensive) lands in a created one.
INSERT INTO spec_group (organisation_id, name, display_order)
SELECT DISTINCT sd.organisation_id, 'Technical', 1
FROM spec_definition sd
WHERE sd.group_id IS NULL
ON CONFLICT (organisation_id, name) DO NOTHING;

UPDATE spec_definition sd
SET group_id = sg.id
FROM spec_group sg
WHERE sd.group_id IS NULL
  AND sg.organisation_id = sd.organisation_id
  AND sg.name = 'Technical';

ALTER TABLE spec_definition
    ALTER COLUMN group_id SET NOT NULL;

ALTER TABLE spec_definition
    DROP COLUMN major_type;

CREATE INDEX idx_spec_definition_group ON spec_definition (group_id);

-- Alternate JSON names for a spec. A source that pushes "vsupply" resolves to the spec whose
-- canonical json_name is "supplyvoltage". Unique per organisation, like json_name itself.
CREATE TABLE spec_alias (
    id                 BIGSERIAL PRIMARY KEY,
    spec_definition_id BIGINT       NOT NULL REFERENCES spec_definition (id) ON DELETE CASCADE,
    organisation_id    BIGINT       NOT NULL REFERENCES organisation (id),
    json_name          VARCHAR(100) NOT NULL,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT spec_alias_org_json_name_key UNIQUE (organisation_id, json_name)
);

CREATE INDEX idx_spec_alias_definition ON spec_alias (spec_definition_id);
