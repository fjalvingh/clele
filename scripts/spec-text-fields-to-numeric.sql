-- Give the measured TEXT spec fields a unit family (and with it, a numeric type).
--
-- WHY
-- ---
-- spec_definition.unit_family is what licenses parsing a spec value string into a number: knowing
-- that a field measures voltage is what turns "5V", "5 V" and "5000 mV" into the one number 5.
-- A field with no family never has its values parsed — they stay in value_text, invisible to every
-- parametric query ("Vds >= 60 V"), and edited in a plain text box. A lot of fields that plainly
-- measure something (every "…voltage", "…current", "…frequency", diameters, delays) were left
-- family-less, mostly because they were created on the fly by an AI lookup or a datasheet import.
--
-- WHAT IT CHANGES
-- ---------------
--   1. TEXT definitions that ALREADY declare a family become NUMBER. Their values are already being
--      parsed (classification looks at the family, not the data type) — only the edit widget was
--      still a text box.
--   2. The definitions listed below get their family, and become NUMBER with it.
-- The `unit` column is deliberately NOT set: a family already names the base unit, and a definition
-- carrying both would render and edit through the older unit + metric_prefix path instead. That
-- matches the 474 existing family-only NUMBER definitions.
--
-- ⚠️ A FAMILY ASSERTS "A BARE NUMBER IN THIS FIELD IS IN THE BASE SI UNIT".
-- Every field below was checked against the values actually stored for it, and the doubtful ones
-- were deliberately left out — torque (N·m, kg·cm, gf·cm), weight (grams, while the SI base is the
-- kilogram), rotational and angular speed (RPM, s/60°), memory sizes (bits, bytes, KB), and the
-- multi-dimension strings ("22.5 x 12 x 35.5 mm"). There is no family that fits those, and an
-- over-eager family is how a 4 KB memory becomes 4000. Note especially that `length` is in METRES:
-- the stored values are all unit-bearing ("22.5 mm"), but a bare 22 typed into one of those fields
-- from now on means 22 m. The editor shows the base unit beside the field name for exactly this.
--
-- AFTER RUNNING IT
-- ----------------
-- Existing values are NOT reclassified by this script — a value already in value_text stays there
-- until its part is saved again (PartSpecValueService.sync is the only thing that parses). Two ways
-- to catch up, both existing: re-save the part, or use POST /spec-definitions/{id}/convert-to-number
-- (dry run first — it lists every value that will not parse and refuses to commit while any remain).
--
-- Repeatable: every statement is guarded, so running it twice changes nothing the second time.

BEGIN;

-- 1 ── a family, but still edited as text ------------------------------------------------------
UPDATE spec_definition
   SET data_type = 'NUMBER'
 WHERE data_type = 'TEXT'
   AND unit_family IS NOT NULL;

-- 2 ── fields whose name and stored values agree on what they measure --------------------------
CREATE TEMP TABLE spec_unit_mapping (json_name text PRIMARY KEY, family text NOT NULL)
    ON COMMIT DROP;

INSERT INTO spec_unit_mapping (json_name, family) VALUES
    -- voltage (V)
    ('Operating voltage',                   'voltage'),
    ('Supply Voltage',                      'voltage'),
    ('Voltage',                             'voltage'),
    ('baseemittersaturationvoltage',        'voltage'),
    ('controlvoltage',                      'voltage'),
    ('highlevelrangevih',                   'voltage'),
    ('inputlogiclevelhigh',                 'voltage'),
    ('inputlogiclevellow',                  'voltage'),
    ('lowlevelrangevil',                    'voltage'),
    ('outputlogiclevelhigh',                'voltage'),
    ('outputlogiclevellow',                 'voltage'),
    ('ratedvoltage',                        'voltage'),
    ('ratedvoltagedc',                      'voltage'),
    ('supplyvoltagesingle',                 'voltage'),
    ('voltageinputdc',                      'voltage'),

    -- current (A)
    ('Locked rotor current',                'current'),
    ('Maximum Continuous Drain Current ID', 'current'),
    ('Operating current',                   'current'),
    ('Output Current',                      'current'),
    ('Output current',                      'current'),
    ('Phase Current',                       'current'),
    ('Quiescent current',                   'current'),
    ('Rated current',                       'current'),
    ('Running Current',                     'current'),
    ('Running current',                     'current'),
    ('Stall Current',                       'current'),
    ('Stall current',                       'current'),
    ('Supply Current',                      'current'),
    ('Supply current',                      'current'),
    ('contactcurrent',                      'current'),
    ('currentoutputhighioh',                'current'),
    ('currentoutputlowiol',                 'current'),
    ('quiescentcurrentiq',                  'current'),
    ('ratedcurrentperphase',                'current'),
    ('receivecurrent',                      'current'),
    ('sendcurrent',                         'current'),
    ('sinkcurrent',                         'current'),
    ('sourcecurrent',                       'current'),

    -- resistance (Ω)
    ('DC Resistance',                       'resistance'),
    ('phaseresistance',                     'resistance'),

    -- power (W)
    ('Output power',                        'power'),
    ('Rated Output power',                  'power'),

    -- frequency (Hz)
    ('Idle Pull-in Frequency',              'frequency'),
    ('Idle Pull-out Frequency',             'frequency'),
    ('Working frequency',                   'frequency'),
    ('clockfrequency',                      'frequency'),
    ('countrate',                           'frequency'),
    ('cpumaximumspeed',                     'frequency'),
    ('frequencyswitching',                  'frequency'),
    ('switchfrequency',                     'frequency'),

    -- time (s) — pulse widths, access times, propagation delays
    ('Dead band width',                     'time'),
    ('Neutral position',                    'time'),
    ('Pulse width range',                   'time'),
    ('accessTime',                          'time'),
    ('holdtime',                            'time'),
    ('maxpropagationdelay',                 'time'),
    ('maximumpropagationdelay',             'time'),
    ('propagationdelaytime',                'time'),
    ('setuptime',                           'time'),

    -- length (m) — ⚠️ the base unit is the METRE, see the warning above
    ('Bodylength',                          'length'),
    ('Diameter',                            'length'),
    ('Height',                              'length'),
    ('Housing diameter',                    'length'),
    ('Housing length',                      'length'),
    ('Length',                              'length'),
    ('Motor diameter',                      'length'),
    ('Outer diameter',                      'length'),
    ('Shaft diameter',                      'length'),
    ('Shaft length',                        'length'),
    ('Width',                               'length'),
    ('bodylength',                          'length'),
    ('shaftdiameter',                       'length'),
    ('shaftlength',                         'length'),

    -- temperature (°C)
    ('Operating temperature range',         'temperature'),
    ('Storage temperature range',           'temperature'),
    ('Temperature range',                   'temperature'),

    -- angle (°)
    ('Limit angle',                         'angle'),
    ('Rotation Angle',                      'angle'),
    ('Rotation angle',                      'angle'),
    ('Running degree',                      'angle'),
    ('Step Angle',                          'angle'),
    ('Stepangle',                           'angle'),
    ('stepangle',                           'angle');

UPDATE spec_definition sd
   SET unit_family = m.family,
       data_type   = 'NUMBER'
  FROM spec_unit_mapping m
 WHERE sd.json_name   = m.json_name
   AND sd.data_type   = 'TEXT'
   AND sd.unit_family IS NULL;

-- 3 ── what the run did, per organisation ------------------------------------------------------
SELECT sd.organisation_id, sd.unit_family, count(*) AS definitions
  FROM spec_definition sd
  JOIN spec_unit_mapping m ON m.json_name = sd.json_name
 GROUP BY sd.organisation_id, sd.unit_family
 ORDER BY sd.organisation_id, sd.unit_family;

-- Mapping entries this database has no field for — expected to differ between installations, and
-- worth reading before assuming a field was converted.
SELECT m.json_name, m.family AS unmatched_mapping_entry
  FROM spec_unit_mapping m
 WHERE NOT EXISTS (SELECT 1 FROM spec_definition sd WHERE sd.json_name = m.json_name)
 ORDER BY m.json_name;

-- TEXT fields whose name still reads like a measurement — the eyeball list for the next pass.
SELECT DISTINCT sd.json_name, sd.name
  FROM spec_definition sd
 WHERE sd.data_type = 'TEXT'
   AND sd.unit_family IS NULL
   AND sd.json_name ~* '(voltage|current|resist|power|frequen|diameter|length|width|height|delay|time|temperature|angle|torque|weight|speed|capacit|induct)'
 ORDER BY sd.json_name;

COMMIT;
