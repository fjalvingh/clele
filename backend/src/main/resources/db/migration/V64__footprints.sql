-- V64: A managed list of footprints (package types).
--
-- part.footprint stays a text column -- every writer (Quick Add, the lookups, kits, the importer)
-- keeps working -- but what it holds is now a name from this list. A writer hands in whatever it
-- has and FootprintService turns it into the canonical name: through an alias ("0805 (2012
-- Metric)" -> "0805") or by ignoring case and punctuation ("sot23" -> "SOT-23").
--
-- The "match key" used below is that comparison: lower case, everything but letters and digits
-- dropped. FootprintService.matchKey is the Java side and must stay the same.

CREATE TABLE footprint (
    id              BIGSERIAL   PRIMARY KEY,
    organisation_id BIGINT      NOT NULL REFERENCES organisation(id) ON DELETE CASCADE,
    name            VARCHAR(64) NOT NULL,
    description     TEXT,
    created_at      TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uq_footprint_name UNIQUE (organisation_id, name)
);

-- Other names a footprint goes by. Unique per organisation, like spec_alias.
CREATE TABLE footprint_alias (
    id              BIGSERIAL   PRIMARY KEY,
    footprint_id    BIGINT      NOT NULL REFERENCES footprint(id) ON DELETE CASCADE,
    organisation_id BIGINT      NOT NULL REFERENCES organisation(id) ON DELETE CASCADE,
    alias           VARCHAR(64) NOT NULL,
    created_at      TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uq_footprint_alias UNIQUE (organisation_id, alias)
);

CREATE INDEX idx_footprint_alias_footprint ON footprint_alias (footprint_id);

-- The common packages every organisation starts with, each with the other names distributors and
-- EDA libraries use for it. A row whose alias is its own canonical name makes the name itself
-- part of the matching below. Chip sizes are imperial; a bare metric code is never an alias,
-- since metric 0603 is imperial 0201.
CREATE TEMPORARY TABLE std_footprint (canonical VARCHAR(64), alias VARCHAR(64));

INSERT INTO std_footprint (canonical, alias) VALUES
    ('01005', '01005'), ('01005', '01005 (0402 Metric)'), ('01005', '0402 Metric'),
    ('0201', '0201'), ('0201', '0201 (0603 Metric)'), ('0201', '0603 Metric'),
    ('0201', 'R_0201_0603Metric'), ('0201', 'C_0201_0603Metric'),
    ('0402', '0402'), ('0402', '0402 (1005 Metric)'), ('0402', '1005 Metric'),
    ('0402', 'R_0402_1005Metric'), ('0402', 'C_0402_1005Metric'), ('0402', 'L_0402_1005Metric'),
    ('0603', '0603'), ('0603', '0603 (1608 Metric)'), ('0603', '1608 Metric'),
    ('0603', 'R_0603_1608Metric'), ('0603', 'C_0603_1608Metric'), ('0603', 'L_0603_1608Metric'),
    ('0603', 'LED_0603_1608Metric'),
    ('0805', '0805'), ('0805', '0805 (2012 Metric)'), ('0805', '2012 Metric'),
    ('0805', 'R_0805_2012Metric'), ('0805', 'C_0805_2012Metric'), ('0805', 'L_0805_2012Metric'),
    ('0805', 'LED_0805_2012Metric'),
    ('1206', '1206'), ('1206', '1206 (3216 Metric)'), ('1206', '3216 Metric'),
    ('1206', 'R_1206_3216Metric'), ('1206', 'C_1206_3216Metric'), ('1206', 'L_1206_3216Metric'),
    ('1206', 'LED_1206_3216Metric'),
    ('1210', '1210'), ('1210', '1210 (3225 Metric)'), ('1210', '3225 Metric'),
    ('1210', 'R_1210_3225Metric'), ('1210', 'C_1210_3225Metric'),
    ('1812', '1812'), ('1812', '1812 (4532 Metric)'), ('1812', '4532 Metric'),
    ('2010', '2010'), ('2010', '2010 (5025 Metric)'), ('2010', '5025 Metric'),
    ('2512', '2512'), ('2512', '2512 (6332 Metric)'), ('2512', '6332 Metric'),
    ('2512', '2512 (6432 Metric)'), ('2512', '6432 Metric'),

    ('SOT-23', 'SOT-23'), ('SOT-23', 'SOT-23-3'), ('SOT-23', 'TO-236'), ('SOT-23', 'TO-236AB'),
    ('SOT-23-5', 'SOT-23-5'), ('SOT-23-5', 'SOT-25'), ('SOT-23-5', 'SOT-753'), ('SOT-23-5', 'SC-74A'),
    ('SOT-23-6', 'SOT-23-6'), ('SOT-23-6', 'SOT-26'), ('SOT-23-6', 'SC-74'),
    ('SOT-223', 'SOT-223'), ('SOT-223', 'SOT-223-3'), ('SOT-223', 'SOT-223-4'), ('SOT-223', 'TO-261'),
    ('SOT-89', 'SOT-89'), ('SOT-89', 'SOT-89-3'), ('SOT-89', 'TO-243'),
    ('SOT-323', 'SOT-323'), ('SOT-323', 'SC-70'), ('SOT-323', 'SC-70-3'),
    ('SOT-353', 'SOT-353'), ('SOT-353', 'SC-70-5'), ('SOT-353', 'SC-88A'),
    ('SOT-363', 'SOT-363'), ('SOT-363', 'SC-70-6'), ('SOT-363', 'SC-88'),
    ('SOT-523', 'SOT-523'), ('SOT-563', 'SOT-563'),
    ('SOD-123', 'SOD-123'), ('SOD-123F', 'SOD-123F'), ('SOD-323', 'SOD-323'), ('SOD-523', 'SOD-523'),
    ('MiniMELF', 'MiniMELF'), ('MiniMELF', 'SOD-80'), ('MELF', 'MELF'),
    ('SMA', 'SMA'), ('SMA', 'DO-214AC'), ('SMB', 'SMB'), ('SMB', 'DO-214AA'),
    ('SMC', 'SMC'), ('SMC', 'DO-214AB'),
    ('DO-35', 'DO-35'), ('DO-41', 'DO-41'), ('DO-201AD', 'DO-201AD'),

    ('TO-92', 'TO-92'), ('TO-92', 'TO-92-3'),
    ('TO-220', 'TO-220'), ('TO-220', 'TO-220-3'), ('TO-220', 'TO-220AB'),
    ('TO-247', 'TO-247'), ('TO-247', 'TO-247-3'),
    ('TO-252', 'TO-252'), ('TO-252', 'DPAK'), ('TO-252', 'TO-252-2'), ('TO-252', 'TO-252-3'),
    ('TO-263', 'TO-263'), ('TO-263', 'D2PAK'), ('TO-263', 'TO-263-2'), ('TO-263', 'TO-263-3'),

    ('SOIC-8', 'SOIC-8'), ('SOIC-8', 'SO-8'), ('SOIC-8', 'SOP-8'), ('SOIC-8', 'SOIC-8_3.9x4.9mm_P1.27mm'),
    ('SOIC-14', 'SOIC-14'), ('SOIC-14', 'SO-14'), ('SOIC-14', 'SOP-14'),
    ('SOIC-16', 'SOIC-16'), ('SOIC-16', 'SO-16'), ('SOIC-16', 'SOP-16'),
    ('MSOP-8', 'MSOP-8'), ('MSOP-10', 'MSOP-10'),
    ('TSSOP-8', 'TSSOP-8'), ('TSSOP-14', 'TSSOP-14'), ('TSSOP-16', 'TSSOP-16'),
    ('TSSOP-20', 'TSSOP-20'), ('TSSOP-24', 'TSSOP-24'), ('TSSOP-28', 'TSSOP-28'),
    ('SSOP-20', 'SSOP-20'), ('SSOP-28', 'SSOP-28'),
    ('DFN-8', 'DFN-8'),
    ('QFN-16', 'QFN-16'), ('QFN-20', 'QFN-20'), ('QFN-24', 'QFN-24'), ('QFN-32', 'QFN-32'),
    ('QFN-48', 'QFN-48'),
    ('TQFP-32', 'TQFP-32'), ('TQFP-44', 'TQFP-44'), ('TQFP-64', 'TQFP-64'),
    ('LQFP-32', 'LQFP-32'), ('LQFP-48', 'LQFP-48'), ('LQFP-64', 'LQFP-64'),
    ('LQFP-100', 'LQFP-100'), ('LQFP-144', 'LQFP-144'),
    ('DIP-8', 'DIP-8'), ('DIP-8', 'PDIP-8'), ('DIP-14', 'DIP-14'), ('DIP-14', 'PDIP-14'),
    ('DIP-16', 'DIP-16'), ('DIP-16', 'PDIP-16'), ('DIP-18', 'DIP-18'), ('DIP-18', 'PDIP-18'),
    ('DIP-20', 'DIP-20'), ('DIP-20', 'PDIP-20'), ('DIP-28', 'DIP-28'), ('DIP-28', 'PDIP-28'),
    ('DIP-40', 'DIP-40'), ('DIP-40', 'PDIP-40');

-- Tidy what parts hold first: no surrounding blanks, and blank means none.
UPDATE part SET footprint = NULLIF(btrim(footprint), '')
WHERE footprint IS DISTINCT FROM NULLIF(btrim(footprint), '');

-- Every spelling in use, per organisation, with its match key. A spelling with no letters or
-- digits at all keys on itself rather than joining every other such spelling.
CREATE TEMPORARY TABLE footprint_in_use AS
SELECT organisation_id,
       footprint                                                   AS spelling,
       count(*)                                                    AS uses,
       coalesce(nullif(lower(regexp_replace(footprint, '[^A-Za-z0-9]', '', 'g')), ''), footprint) AS k,
       CAST(NULL AS VARCHAR(64))                                   AS canonical
FROM part
WHERE footprint IS NOT NULL
GROUP BY organisation_id, footprint;

-- A spelling that matches a standard package (by its name or any alias) becomes that package.
UPDATE footprint_in_use u
SET canonical = s.canonical
FROM std_footprint s
WHERE lower(regexp_replace(s.alias, '[^A-Za-z0-9]', '', 'g')) = u.k;

-- Everything else groups on its match key, under the spelling most parts already use.
UPDATE footprint_in_use u
SET canonical = w.spelling
FROM (SELECT DISTINCT ON (organisation_id, k) organisation_id, k, spelling
      FROM footprint_in_use
      WHERE canonical IS NULL
      ORDER BY organisation_id, k, uses DESC, spelling) w
WHERE u.canonical IS NULL
  AND u.organisation_id = w.organisation_id
  AND u.k = w.k;

INSERT INTO footprint (organisation_id, name)
SELECT o.id, s.canonical
FROM organisation o
CROSS JOIN (SELECT DISTINCT canonical FROM std_footprint) s;

INSERT INTO footprint (organisation_id, name)
SELECT DISTINCT u.organisation_id, u.canonical
FROM footprint_in_use u
WHERE NOT EXISTS (SELECT 1 FROM std_footprint s WHERE s.canonical = u.canonical);

-- Only the aliases that say something the match key does not: "SOT23" needs no alias to find
-- "SOT-23", "TO-236" does.
INSERT INTO footprint_alias (footprint_id, organisation_id, alias)
SELECT f.id, f.organisation_id, s.alias
FROM std_footprint s
JOIN footprint f ON f.name = s.canonical
WHERE lower(regexp_replace(s.alias, '[^A-Za-z0-9]', '', 'g'))
      <> lower(regexp_replace(s.canonical, '[^A-Za-z0-9]', '', 'g'));

UPDATE part p
SET footprint = u.canonical
FROM footprint_in_use u
WHERE p.organisation_id = u.organisation_id
  AND p.footprint = u.spelling
  AND u.spelling <> u.canonical;

DROP TABLE footprint_in_use;
DROP TABLE std_footprint;
