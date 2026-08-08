-- Move the Parts free-text search off part.specs and onto the typed rows -- step 4 of the typed
-- spec value migration (see SPECS-REWRITE.md).
--
-- V43 indexed ONE concatenated vector over description || details || the string values of the specs
-- JSONB, and that concatenation is load-bearing rather than incidental: a tsquery ANDs its terms, so
-- "transistor sot-23" only matches when every term is in the same vector. Matched separately and
-- OR'd, a term from the description and a term from the specs could never satisfy either side.
--
-- The spec strings now live in part_spec_value.value_text, in another table, and an expression index
-- cannot reach across one. Two ways out, and this is the second:
--
--   1. a correlated subquery aggregating value_text at query time -- correct, but indexable by
--      nothing, so every search becomes a full scan that runs to_tsvector per part. Fine at 1,102
--      parts, not at ten times that, and this is a database meant to grow.
--   2. materialise the concatenated spec text on the part, and index the same single expression V43
--      indexed. Search keeps exactly the semantics it has today AND stays on a GIN index.
--
-- spec_text is a SEARCH INDEX, not a stored rendering. The rewrite refuses a stored `display`
-- because a rendering that drifts shows a wrong number to a user; a search projection that drifts
-- costs a missed hit and is rebuilt by the same write path that maintains the rows
-- (PartSpecValueService.sync writes it, and it is the only way a spec value is written).
-- Materialising a tsvector source column is the ordinary PostgreSQL pattern for precisely this.
--
-- Populated here from the JSONB rather than from part_spec_value, deliberately: the JSONB is still
-- authoritative at this step and is present on every installation, whereas the rows are only filled
-- once the specvalues backfill has been run. Same source V43 indexed, so the migration cannot change
-- what any existing search matches.

ALTER TABLE part ADD COLUMN spec_text TEXT;

COMMENT ON COLUMN part.spec_text IS
    'Concatenated string spec values, maintained by PartSpecValueService.sync. Search projection only.';

UPDATE part p
SET spec_text = (
    SELECT string_agg(e.value, ' ')
    FROM jsonb_each_text(coalesce(p.specs, '{}')) e
    WHERE jsonb_typeof(p.specs -> e.key) = 'string'
);

-- Same shape as V43's index, with the jsonb term replaced by the column. PartRepository.search
-- spells this expression verbatim -- editing one side without the other silently costs the index,
-- not correctness, so verify with EXPLAIN if either moves.
DROP INDEX IF EXISTS idx_part_search_fts;

CREATE INDEX idx_part_search_fts
    ON part USING GIN ((
        to_tsvector('english', coalesce(description, ''))
        || to_tsvector('english', coalesce(details, ''))
        || to_tsvector('english', coalesce(spec_text, ''))
    ));

-- No index is added for the sparse-specs count, which now counts rows per part: part_id is already
-- the leading column of part_spec_value's primary key, so that count is an index-only scan.
