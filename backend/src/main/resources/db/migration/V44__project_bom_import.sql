-- BOM import: an uploaded bill of materials attached to a project, and its lines.
--
-- One BOM per project (UNIQUE project_id): re-uploading a revised export MERGES into the existing
-- BOM rather than replacing it, so the matching work already done survives a schematic revision.
--
-- Neither table carries organisation_id. They reach it through project_id, exactly as
-- project_part and project_stock do (see V36__organisations.sql) -- there is then nothing that can
-- drift out of sync with the project's own organisation.

CREATE TABLE project_bom (
    id             BIGSERIAL PRIMARY KEY,
    project_id     BIGINT       NOT NULL UNIQUE REFERENCES project(id) ON DELETE CASCADE,
    filename       VARCHAR(255),
    content_type   VARCHAR(128),
    data           BYTEA,                      -- the last uploaded file, verbatim
    column_mapping JSONB,                      -- remembered; pre-fills the next upload's mapping
    imported_at    TIMESTAMP    NOT NULL,
    imported_by_id BIGINT REFERENCES app_user(id) ON DELETE SET NULL
);

-- reference_key is the merge key: the line's designators normalised (uppercased, split on comma or
-- whitespace, naturally sorted, rejoined). A file with no designator column falls back to a key
-- derived from mpn/value/line number, so the key is always present and unique within a BOM.
--
-- status is BomLineStatus: UNMATCHED / MATCHED / PROVIDED / EXCLUDED.
--   PROVIDED = an uncatalogued commodity assumed to be on hand (a resistor from the drawer).
--   EXCLUDED = deliberately not fitted; set automatically from the file's DNP column.
-- match_source is BomMatchSource: AUTO (an unambiguous exact hit at import) or MANUAL (confirmed
-- by the user). A re-import never overwrites a MANUAL match.
--
-- part_id is ON DELETE SET NULL: deleting a part must not delete BOM history. That can leave a row
-- reading MATCHED with no part; the DTO mapper reports such a line as UNMATCHED rather than
-- writing to the database on a read.
CREATE TABLE project_bom_line (
    id            BIGSERIAL PRIMARY KEY,
    bom_id        BIGINT       NOT NULL REFERENCES project_bom(id) ON DELETE CASCADE,
    line_no       INT          NOT NULL,
    reference_key VARCHAR(512) NOT NULL,
    designators   TEXT,
    value         VARCHAR(255),
    footprint     VARCHAR(255),
    mpn           VARCHAR(128),
    manufacturer  VARCHAR(255),
    description   TEXT,
    datasheet_url TEXT,
    quantity      INT          NOT NULL,       -- per build instance (per board)
    dnp           BOOLEAN      NOT NULL DEFAULT FALSE,
    extra         JSONB,                       -- columns the mapping did not claim, kept verbatim
    status        VARCHAR(20)  NOT NULL,
    match_source  VARCHAR(20),
    part_id       BIGINT REFERENCES part(id) ON DELETE SET NULL,
    changed       BOOLEAN      NOT NULL DEFAULT FALSE,  -- value/footprint moved in the last merge
    notes         TEXT,
    CONSTRAINT uq_project_bom_line UNIQUE (bom_id, reference_key)
);

CREATE INDEX idx_project_bom_line_bom ON project_bom_line(bom_id);

-- part_number has had a trigram index since V15 (Quick Add's fuzzy lookup). A BOM line is just as
-- often keyed on the manufacturer part number, so make that fuzzy-matchable the same way.
CREATE INDEX idx_part_mpn_trgm ON part USING gin (mpn gin_trgm_ops);
