-- Part kit templates: one stored definition for a pack of parts that differ in a single value.
--
-- A resistor kit is 30 parts that share manufacturer, footprint, tolerance, power rating and
-- category, and differ only in resistance. Entering them one at a time through Quick Add is 30
-- rounds of the same form. A kit template holds the part fields once, with the value placeholder
-- wherever the varying value belongs, plus the list of values -- and "Generate parts" expands the
-- two into real parts with stock.
--
-- (The placeholder is dollar-brace "value" -- deliberately not spelled out anywhere in this file,
-- because that is also Flyway's own placeholder syntax and it fails the migration on sight. See
-- PartKitTemplateService.PLACEHOLDER for the literal.)
--
-- Every template field is a TEXT template, not the column's own type: "10k" is not a number and a
-- placeholder is not a URL, so nothing here can be typed more strictly than the substitution
-- allows. The generated part is what gets the real columns.
--
-- specs is jsonb keyed by spec_definition.json_name exactly as part.specs is -- the values are
-- templates too, which is the point (a resistance spec whose whole value is the placeholder).
CREATE TABLE part_kit_template (
    id                    BIGSERIAL PRIMARY KEY,
    organisation_id       BIGINT       NOT NULL REFERENCES organisation(id),
    name                  VARCHAR(255) NOT NULL,
    notes                 TEXT,

    -- The part template. Mirrors the New Part form, field for field.
    part_number_template  TEXT         NOT NULL,
    personal_number       BOOLEAN      NOT NULL DEFAULT FALSE,
    manufacturer_template TEXT,
    description_template  TEXT,
    details_template      TEXT,
    footprint_template    TEXT,
    datasheet_url_template TEXT,
    category_id           BIGINT REFERENCES category(id) ON DELETE SET NULL,
    specs                 JSONB,

    created_by_id         BIGINT       NOT NULL REFERENCES app_user(id),
    created_at            TIMESTAMP    NOT NULL,
    updated_at            TIMESTAMP    NOT NULL,

    CONSTRAINT uq_part_kit_template_name UNIQUE (organisation_id, name)
);

CREATE INDEX idx_part_kit_template_org ON part_kit_template(organisation_id);

-- The values the kit varies over, in the order the user entered them. Unique per template: the
-- same value twice would generate the same part twice, which is a mistake, not a request.
CREATE TABLE part_kit_template_value (
    id            BIGSERIAL PRIMARY KEY,
    template_id   BIGINT      NOT NULL REFERENCES part_kit_template(id) ON DELETE CASCADE,
    value         VARCHAR(255) NOT NULL,
    display_order INT         NOT NULL,

    CONSTRAINT uq_part_kit_template_value UNIQUE (template_id, value)
);

CREATE INDEX idx_part_kit_template_value_template ON part_kit_template_value(template_id);

-- Tag names rather than tag ids: a template names the tags its parts should carry, and
-- TagService.resolveOrCreate turns them into rows at generate time -- the same path every other
-- intake uses. Storing ids would also make a placeholder impossible in a tag.
CREATE TABLE part_kit_template_tag (
    template_id BIGINT       NOT NULL REFERENCES part_kit_template(id) ON DELETE CASCADE,
    tag         VARCHAR(255) NOT NULL,

    PRIMARY KEY (template_id, tag)
);
