-- Merge spec definitions that are the same specification under a different source name.
--
-- Each source's json_name becomes an alias of the surviving definition (so a later update from the
-- source that uses it still lands there rather than recreating the duplicate), every part value is
-- re-keyed onto the survivor, category links are re-pointed, and the source definition is dropped.
-- This is the SQL equivalent of SpecDefinitionService.merge, applied in bulk to every organisation.
--
-- Only same-data_type merges are here. Where the concept matches but the types differ, the two hold
-- different value shapes (a TEXT spec carries Partsbox ranges like "3..16", a NUMBER spec a scalar),
-- so merging would put range strings into a numeric field. Those are left for the Spec Fields screen
-- to handle after a convert-to-number: operatingsupplyvoltage/supplyvoltage,
-- powerconsumption/powerdissipation, reversevoltage_dc_/reversevoltage, dielectric/dielectricmaterial,
-- breakdownvoltage/reversebreakdownvoltage. Also deliberately NOT merged: lifecyclestatus and
-- manufacturerlifecyclestatus (same concept, incompatible vocabularies — "Production" vs "ACTIVE"),
-- density and memorysize (bits vs bytes), hscode and htscode (different code systems), and
-- numberofelements and numberofcircuits (distinct counts on multi-element parts).
--
-- Re-runnable: once a source is gone every join below matches nothing.

CREATE TEMP TABLE spec_merge_map (target VARCHAR(100), source VARCHAR(100));

INSERT INTO spec_merge_map (target, source) VALUES
    ('supplyvoltage', 'supplyvoltage_dc_'),
    ('inputvoltage', 'inputvoltage_dc_'),
    ('operatingtemperature', 'ambienttemperaturerange'),
    ('supplycurrent', 'operatingsupplycurrent'),
    ('gainbandwidthproduct', 'nominalgainbandwidthproduct'),
    ('gainbandwidthproduct', 'unitygainbandwidthproduct'),
    ('numberofadcchannels', 'numberofa_dconverters'),
    ('numberofdacchannels', 'numberofd_aconverters'),
    ('numberofi_os', 'numberofprogrammablei_o'),
    ('voltagerating', 'workingvoltage'),
    ('draintosourceresistance', 'rdsonmax'),
    ('draintosourceresistance', 'on_stateresistance'),
    ('collectoremittervoltage_vceo_', 'collectoremitterbreakdownvoltage'),
    ('draintosourcevoltage_vdss_', 'draintosourcebreakdownvoltage'),
    ('maxcollectorcurrent', 'continuouscollectorcurrent'),
    ('gatetosourcevoltage_vgs_', 'nominalvgs'),
    ('maxforwardsurgecurrent_ifsm_', 'peaknon_repetitivesurgecurrent'),
    ('maxforwardsurgecurrent_ifsm_', 'maxsurgecurrent'),
    ('leakagecurrent', 'maxreverseleakagecurrent'),
    ('leakagecurrent', 'peakreversecurrent'),
    ('reversevoltage', 'maxrepetitivereversevoltage_vrrm_'),
    ('numberofcircuits', 'independentcircuits'),
    ('contactgender', 'gender'),
    ('contactplating', 'plating');

-- 1. Re-key part values from the source keys onto the target key. Rebuilding each part's specs in
--    one pass handles a part that holds several sources for the same target. The ORDER BY decides
--    who wins a collision: sources first, the target's own non-blank value last (so it wins), a
--    blank target value first (so a real source value replaces it).
UPDATE part p
SET specs = r.new_specs
FROM (SELECT p2.id,
             jsonb_object_agg(COALESCE(m.target, e.key), e.value
                              ORDER BY CASE
                                           WHEN m.target IS NOT NULL THEN 1
                                           WHEN btrim(COALESCE(e.value #>> '{}', '')) = '' THEN 0
                                           ELSE 2
                                           END) AS new_specs
      FROM part p2
               CROSS JOIN LATERAL jsonb_each(p2.specs) e
               LEFT JOIN spec_merge_map m ON m.source = e.key
      WHERE EXISTS (SELECT 1 FROM spec_merge_map m2 WHERE p2.specs ? m2.source)
      GROUP BY p2.id) r
WHERE p.id = r.id;

-- 2. The source's name becomes an alias of the target, per organisation.
INSERT INTO spec_alias (spec_definition_id, organisation_id, json_name)
SELECT t.id, t.organisation_id, s.json_name
FROM spec_merge_map m
         JOIN spec_definition t ON t.json_name = m.target
         JOIN spec_definition s ON s.json_name = m.source AND s.organisation_id = t.organisation_id
ON CONFLICT (organisation_id, json_name) DO NOTHING;

-- 2b. Any alias the source already carried moves to the target too (nothing has one yet, but a
--     second run of a merge chain would).
UPDATE spec_alias a
SET spec_definition_id = t.id
FROM spec_merge_map m
         JOIN spec_definition s ON s.json_name = m.source
         JOIN spec_definition t ON t.json_name = m.target AND t.organisation_id = s.organisation_id
WHERE a.spec_definition_id = s.id;

-- 3. Category links move to the target, dropping the ones that would collide with an existing link.
DELETE
FROM category_spec cs
    USING spec_merge_map m
    JOIN spec_definition s ON s.json_name = m.source
    JOIN spec_definition t ON t.json_name = m.target AND t.organisation_id = s.organisation_id
WHERE cs.spec_id = s.id
  AND EXISTS (SELECT 1 FROM category_spec cs2 WHERE cs2.category_id = cs.category_id AND cs2.spec_id = t.id);

UPDATE category_spec cs
SET spec_id = t.id
FROM spec_merge_map m
         JOIN spec_definition s ON s.json_name = m.source
         JOIN spec_definition t ON t.json_name = m.target AND t.organisation_id = s.organisation_id
WHERE cs.spec_id = s.id;

-- 4. Drop the merged-away definitions — only where the target actually exists in that organisation.
DELETE
FROM spec_definition s
    USING spec_merge_map m
WHERE s.json_name = m.source
  AND EXISTS (SELECT 1
              FROM spec_definition t
              WHERE t.json_name = m.target
                AND t.organisation_id = s.organisation_id);

DROP TABLE spec_merge_map;
