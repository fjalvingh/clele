-- Typed spec values, replacing the loose part.specs JSONB map.
--
-- One row per (part, spec definition), holding exactly one of three shapes:
--   * a parsed scalar        -> value_num              ("100nF" against capacitance -> 1e-7)
--   * a parsed range         -> value_min / value_max  ("4.5..null" -> min 4.5, max open)
--   * a raw string           -> value_text             (TEXT/SELECT/BOOLEAN, and anything that
--                                                       did not parse cleanly)
--
-- Numbers are stored in the definition's base SI unit, so comparison and search just work: a part
-- with "100nF", one with "0.1 uF" and one with "1e-7" all land on the same number and all answer
-- "capacitance = 100 nF". Today those are three different strings and none of them answers anything.
--
-- NUMERIC, not double precision: the component cache needs a separate value_exact column precisely
-- because its source numbers are JSON doubles (1e-7 arrives as 1.0000000000000001e-07, so "=" fails
-- silently). NUMERIC serves both "=" and range comparisons from one column.
--
-- There is deliberately no stored rendering. The cache stores one because it is a read-only snapshot
-- of someone else's parse; here we do the parsing, so a rendering would be pure denormalisation with
-- nothing keeping it in step when the value is edited. Display is derived every time, by the exact
-- inverse of the parser (MetricUnitFormatter / units.ts).
--
-- No organisation_id: the row reaches its tenant through part_id, like every other per-part table.

CREATE TABLE part_spec_value (
    part_id            BIGINT  NOT NULL REFERENCES part(id) ON DELETE CASCADE,
    spec_definition_id BIGINT  NOT NULL REFERENCES spec_definition(id) ON DELETE CASCADE,
    value_num          NUMERIC,
    value_min          NUMERIC,
    value_max          NUMERIC,
    value_text         TEXT,
    PRIMARY KEY (part_id, spec_definition_id),
    -- Parsed or raw, never both: whatever was extracted is the only copy, so nothing can drift.
    CONSTRAINT part_spec_value_one_shape CHECK (
        num_nonnulls(value_num, value_text)
        + (value_min IS NOT NULL OR value_max IS NOT NULL)::int = 1
    )
);

-- One indexed EXISTS per parametric criterion ("Vds >= 60 V"), which is the query the JSONB could
-- never express. Partial, because a spec is either numeric or textual and never both.
CREATE INDEX idx_psv_def_num  ON part_spec_value (spec_definition_id, value_num)
    WHERE value_num IS NOT NULL;
CREATE INDEX idx_psv_def_text ON part_spec_value (spec_definition_id, value_text)
    WHERE value_text IS NOT NULL;
-- Range containment ("supply voltage covers 3.3 V" -> value_min <= 3.3 AND value_max >= 3.3).
CREATE INDEX idx_psv_def_range ON part_spec_value (spec_definition_id, value_min, value_max)
    WHERE value_min IS NOT NULL OR value_max IS NOT NULL;

COMMENT ON TABLE part_spec_value IS
    'Typed spec values in SI base units. Exactly one of value_num / (value_min,value_max) / value_text.';

-- What a spec field measures. This is what licenses parsing a value string into a number at all:
-- knowing capacitance is measured in farads is what turns "100nF" into 1e-7. Values mirror the
-- component cache families (see UnitFamily.java) so the two translations agree.
--
-- A definition with NO family never has its values parsed -- they stay text. That is the safe
-- default and deliberately not a gap to be filled in for tidiness: an over-eager family is how a
-- 4 KB memory becomes 4000. Leave a field family-less unless its base unit is genuinely certain.
ALTER TABLE spec_definition ADD COLUMN unit_family VARCHAR(40);

COMMENT ON COLUMN spec_definition.unit_family IS
    'UnitFamily code (resistance, capacitance, length, ...). NULL = never parse, values stay text.';
