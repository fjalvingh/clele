-- Full-text search index on part.description so the Parts search (name/part_number substring +
-- description FTS) stays fast as the catalogue grows. Matches the query in PartRepository.search,
-- which uses to_tsvector('english', description) @@ websearch_to_tsquery('english', :term).
CREATE INDEX IF NOT EXISTS idx_part_description_fts
    ON part USING GIN (to_tsvector('english', coalesce(description, '')));
