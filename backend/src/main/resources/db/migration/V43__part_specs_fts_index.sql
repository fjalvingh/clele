-- Bring part.details and part.specs into the Parts free-text search.
--
-- The catalogue holds a large, carefully curated body of specification data (355 fields in a
-- 19-group taxonomy, V40-V42) that the search could not reach: it matched part_number and
-- description only. A user looking for "sot-23" or "0805" knows the package, essentially never
-- the MPN, so the specs are exactly what they are searching by. `details` is the other omission
-- and the more annoying one: it is free text the user typed themselves and then could not find.
--
-- The three vectors are concatenated into ONE indexed expression rather than being matched
-- separately and OR'd. A tsquery ANDs its terms, so a multi-word search like "transistor sot-23"
-- only matches when every term is in the same vector -- with separate conditions, a term from the
-- description and a term from the specs could never satisfy either side. The concatenation makes
-- all three fields one searchable document.
--
-- Only STRING spec values are indexed. Numbers are stored in base SI units (a 7.62mm width is
-- 0.00762, a 33ns delay 0.000000033), which tokenise into strings nobody will ever type; they
-- would only bloat the index. Values that read as numbers but are stored as text (Partsbox ranges
-- like "4.75..5.25") are strings and come along.
--
-- Replaces the description-only index from V9: the query no longer uses that expression, so the
-- old index could never be chosen again.
--
-- PartRepository.search spells the same expression without the '::jsonb' cast (a bare '{}'), to
-- keep '::' out of a Hibernate native query where ':' introduces a named parameter. PostgreSQL
-- resolves the untyped literal to jsonb in both, so the two normalise to one expression and the
-- index still matches -- verify with EXPLAIN if either side is edited.

DROP INDEX IF EXISTS idx_part_description_fts;

CREATE INDEX IF NOT EXISTS idx_part_search_fts
    ON part USING GIN ((
        to_tsvector('english', coalesce(description, ''))
        || to_tsvector('english', coalesce(details, ''))
        || jsonb_to_tsvector('english', coalesce(specs, '{}'::jsonb), '["string"]')
    ));
