-- Mark a numeric spec whose stored value is in a base SI unit (the `unit` column),
-- so the UI renders/edits it with the appropriate metric prefix (e.g. 0.009 A -> "9 mA").
ALTER TABLE spec_definition ADD COLUMN metric_prefix BOOLEAN NOT NULL DEFAULT FALSE;
