-- Fuzzy part-number matching for Quick Add: before searching the Internet, Quick Add looks for an
-- existing part whose part_number is similar to the typed term. pg_trgm provides trigram similarity
-- (typo/transposition tolerant) and the GIN index keeps the `%` similarity operator fast.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_part_part_number_trgm ON part USING gin (part_number gin_trgm_ops);
