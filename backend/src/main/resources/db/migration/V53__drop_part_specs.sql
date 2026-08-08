-- Drop part.specs -- step 6, the last step of the typed spec value migration and the only
-- irreversible one. From here the typed part_spec_value rows are the storage, not a mirror.
--
-- ============================================================================================
-- WHY THIS MIGRATION CARRIES ITS OWN DATA MIGRATION
-- ============================================================================================
-- The plan was: run the specvalues backfill CLI, let it soak, then drop the column. That is safe
-- on an installation already running V52. It is NOT safe on one still on an older version, and
-- ours was: Flyway applies V50..V53 in a single run, so the column would be dropped in the same
-- breath that created the table meant to replace it, destroying every spec value in the database.
--
-- The CLI could not have saved that installation either -- it runs Flyway on startup too, so it
-- would hit this migration before it could copy anything. The migration therefore has to be
-- self-sufficient: it fills in whatever is missing before dropping the source.
--
-- On an installation where the backfill already ran, the INSERT below matches nothing and this is
-- a no-op that only drops the column.
--
-- FIDELITY. SQL cannot reproduce everything the Java classifier does -- it cannot parse "150 ns"
-- against a unit family, does not know RKM, and does not recognise the "A ~ B" / "A to B" range
-- spellings. Measured by running both over the development catalogue: 21,719 values either way, of
-- which the Java backfill types 15 more (11,759 scalars + 1,492 ranges against 11,754 + 1,482).
-- Those 15 stay text here -- visible, searchable and correctable by hand, not lost. Running the
-- backfill before upgrading avoids even that; this is the safety net, not the recommended path.

-- A value can only become a row if its key has a spec_definition, and the Java path creates one on
-- first sight. Without this step the JOIN below silently drops those values -- measured: 7 of them
-- on the development catalogue, which is exactly the kind of quiet loss this migration exists to
-- prevent. The title is a rough initcap rather than SpecNameHumanizer's segmentation (which is
-- Java), so a rescan or a rename tidies it later; the value is what matters here.
INSERT INTO spec_definition (organisation_id, json_name, name, data_type, display_order,
                             metric_prefix, group_id, created_at)
SELECT DISTINCT ON (p.organisation_id, e.key)
       p.organisation_id,
       e.key,
       initcap(replace(e.key, '_', ' ')),
       'TEXT',
       0,
       FALSE,
       (SELECT g.id FROM spec_group g
        WHERE g.organisation_id = p.organisation_id
        ORDER BY (lower(g.name) = 'technical') DESC, g.display_order, g.name
        LIMIT 1),
       now()
FROM part p
JOIN LATERAL jsonb_each_text(coalesce(p.specs, '{}')) AS e(key, value_text) ON TRUE
WHERE trim(e.value_text) <> ''
  AND NOT EXISTS (SELECT 1 FROM spec_definition sd
                  WHERE sd.json_name = e.key AND sd.organisation_id = p.organisation_id)
  -- An organisation with no spec groups at all has nowhere to file it; there is no such
  -- organisation after V41, and inventing a group here would be a bigger surprise than skipping.
  AND EXISTS (SELECT 1 FROM spec_group g WHERE g.organisation_id = p.organisation_id);

-- Numbers, rounded to 12 significant digits exactly as PartSpecValueService.storedScale does.
-- Without the rounding, a value that arrived as the JSON double 1.0000000000000001e-7 stays
-- un-findable by "capacitance = 100 nF" -- see that method for why this is not cosmetic.
--
-- ⚠️ A JSON *number* always converts; a JSON *string* must survive a losslessness test first. That
-- is the rule PartSpecValueService applies, and telling the two apart is why this reads jsonb_each
-- rather than jsonb_each_text -- the text form flattens them and the distinction disappears.
--
-- The test refuses a LEADING ZERO before another digit, which is numericIfLossless as a regex:
-- "0805" is an imperial case code, not the number 805, and converting it destroys the value and
-- drops it out of the free-text search. This statement got that wrong on its first run and turned
-- 0805 into 805 and 0603 into 603 -- the same bug the Java classifier had. It also refuses a
-- trailing zero after a decimal point ("1.50" reads back as 1.5) and scientific notation; those
-- stay text rather than come back looking different.
INSERT INTO part_spec_value (part_id, spec_definition_id, value_num, value_min, value_max, value_text)
SELECT p.id,
       sd.id,
       CASE WHEN t.numeric_ok
            THEN trim_scale(round(t.txt::numeric, greatest(0, 12 - (1 + floor(
                     log(10, abs(nullif(t.txt::numeric, 0))))::int))))
            END,
       CASE WHEN NOT t.numeric_ok AND t.txt ~ '^-?[0-9.]+\.\.'
            THEN trim_scale(split_part(t.txt, '..', 1)::numeric) END,
       CASE WHEN NOT t.numeric_ok AND t.txt ~ '\.\.-?[0-9.]+$'
            THEN trim_scale(split_part(t.txt, '..', 2)::numeric) END,
       CASE WHEN t.numeric_ok THEN NULL
            WHEN t.txt ~ '\.\.' AND (t.txt ~ '^-?[0-9.]+\.\.' OR t.txt ~ '\.\.-?[0-9.]+$') THEN NULL
            ELSE t.txt END
FROM part p
JOIN LATERAL jsonb_each(coalesce(p.specs, '{}')) AS e(key, value) ON TRUE
JOIN LATERAL (SELECT e.value #>> '{}' AS txt,
                     jsonb_typeof(e.value) = 'number'
                     OR (jsonb_typeof(e.value) = 'string'
                         AND (e.value #>> '{}') ~ '^-?(0|[1-9][0-9]*)(\.[0-9]*[1-9])?$') AS numeric_ok
             ) t ON TRUE
JOIN spec_definition sd ON sd.json_name = e.key AND sd.organisation_id = p.organisation_id
WHERE trim(t.txt) <> ''
  -- Only what is missing: a value already carried into the rows is authoritative, because the Java
  -- classifier understood more about it than this statement can.
  AND NOT EXISTS (SELECT 1 FROM part_spec_value v
                  WHERE v.part_id = p.id AND v.spec_definition_id = sd.id)
ON CONFLICT DO NOTHING;

-- The same for the search projection, which is derived from the text values.
UPDATE part p
SET spec_text = (
    SELECT string_agg(v.value_text, ' ')
    FROM part_spec_value v
    WHERE v.part_id = p.id AND v.value_text IS NOT NULL
)
WHERE p.spec_text IS NULL;

-- The column is now unreferenced: reads moved to the rows in V52, and writes go through
-- PartSpecValueService.sync, which takes the map as an argument rather than off the entity.
ALTER TABLE part DROP COLUMN specs;
