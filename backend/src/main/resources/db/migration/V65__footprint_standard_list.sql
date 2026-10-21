-- V65: A wider standard footprint list, and the in-use spellings folded into it.
--
-- V64 seeded the common SMD packages. This adds what an older or through-hole stock holds: metal
-- cans (TO-18, TO-66, TO-99, ...), SIP, wide and ceramic DIP, PLCC, crystals, bridge rectifiers,
-- axial and radial passives and valve sockets. The names follow JEDEC and IPC-7351 where those
-- have one, in the short form distributors use ("TO-66", not "TO-213AA"); the registered outline is
-- an alias.
--
-- Then, per organisation, every footprint whose name matches a standard entry (by match key, or by
-- the part before a trailing "(...)", the distributor habit "SMA(DO-214AC)") is merged into it,
-- exactly as the Footprints screen merges: its parts move, its aliases move, its name becomes an
-- alias. And the spellings that are not a package at all -- the component cache's "插件"
-- (through-hole) with or without dimensions, "SMD,19.2x18mm", "Through hole" -- are cleared
-- from the parts and dropped from the list. FootprintService.isPackageName is the same rule.
--
-- DIP widths: a plain DIP name is the 300 mil one. DIP-24 and DIP-28 come in both widths, and the
-- 600 mil one (JEDEC MS-011) is its own footprint, "DIP-28W", since neither fits the other's
-- holes. DIP-32 and up only exist wide, so their plain name is the 600 mil one. A part that was on
-- DIP-24 or DIP-28 before this migration said no width, and in this stock those are the wide
-- memories and older logic, so it moves to the W footprint; only a spelling that said "skinny"
-- (SPDIP, SKDIP) lands on the plain one.

CREATE TEMPORARY TABLE std_footprint (canonical VARCHAR(64), alias VARCHAR(64));

-- A row whose alias is its own canonical name makes the name itself part of the matching, as in
-- V64. Rows for a V64 canonical only add aliases to it.
INSERT INTO std_footprint (canonical, alias) VALUES
    -- chip sizes V64 did not have
    ('1008', '1008'), ('1008', '1008 (2520 Metric)'), ('1008', '2520 Metric'),
    ('1008', 'L_1008_2520Metric'),
    ('2220', '2220'), ('2220', '2220 (5750 Metric)'), ('2220', '5750 Metric'),
    ('2220', 'C_2220_5750Metric'),

    -- tantalum and polymer chip capacitors, by EIA case
    ('EIA-3216-18', 'EIA-3216-18'), ('EIA-3216-18', 'Case A'), ('EIA-3216-18', 'CASE-A-3216-18'),
    ('EIA-3216-18', 'CP_EIA-3216-18_Kemet-A'),
    ('EIA-3528-21', 'EIA-3528-21'), ('EIA-3528-21', 'Case B'), ('EIA-3528-21', 'CASE-B-3528-21'),
    ('EIA-3528-21', 'CP_EIA-3528-21_Kemet-B'),
    ('EIA-6032-28', 'EIA-6032-28'), ('EIA-6032-28', 'Case C'), ('EIA-6032-28', 'CASE-C-6032-28'),
    ('EIA-6032-28', 'CP_EIA-6032-28_Kemet-C'),
    ('EIA-7343-31', 'EIA-7343-31'), ('EIA-7343-31', 'Case D'), ('EIA-7343-31', 'CASE-D-7343-31'),
    ('EIA-7343-31', 'CP_EIA-7343-31_Kemet-D'),

    -- axial resistors, by DIN body size
    ('DIN 0204', 'DIN 0204'), ('DIN 0204', 'Axial DIN0204'), ('DIN 0204', 'R_Axial_DIN0204'),
    ('DIN 0207', 'DIN 0207'), ('DIN 0207', 'Axial DIN0207'), ('DIN 0207', 'R_Axial_DIN0207'),
    ('DIN 0309', 'DIN 0309'), ('DIN 0309', 'Axial DIN0309'), ('DIN 0309', 'R_Axial_DIN0309'),
    ('DIN 0411', 'DIN 0411'), ('DIN 0411', 'Axial DIN0411'), ('DIN 0411', 'R_Axial_DIN0411'),
    ('DIN 0414', 'DIN 0414'), ('DIN 0414', 'Axial DIN0414'), ('DIN 0414', 'R_Axial_DIN0414'),
    ('DIN 0617', 'DIN 0617'), ('DIN 0617', 'Axial DIN0617'), ('DIN 0617', 'R_Axial_DIN0617'),
    ('DIN 0918', 'DIN 0918'), ('DIN 0918', 'Axial DIN0918'), ('DIN 0918', 'R_Axial_DIN0918'),

    -- radial electrolytics, by can diameter and lead pitch
    ('Radial D4mm P1.5mm', 'Radial D4mm P1.5mm'), ('Radial D4mm P1.5mm', 'CP_Radial_D4.0mm_P1.50mm'),
    ('Radial D5mm P2mm', 'Radial D5mm P2mm'), ('Radial D5mm P2mm', 'CP_Radial_D5.0mm_P2.00mm'),
    ('Radial D6.3mm P2.5mm', 'Radial D6.3mm P2.5mm'), ('Radial D6.3mm P2.5mm', 'CP_Radial_D6.3mm_P2.50mm'),
    ('Radial D8mm P3.5mm', 'Radial D8mm P3.5mm'), ('Radial D8mm P3.5mm', 'CP_Radial_D8.0mm_P3.50mm'),
    ('Radial D10mm P5mm', 'Radial D10mm P5mm'), ('Radial D10mm P5mm', 'CP_Radial_D10.0mm_P5.00mm'),
    ('Radial D12.5mm P5mm', 'Radial D12.5mm P5mm'), ('Radial D12.5mm P5mm', 'CP_Radial_D12.5mm_P5.00mm'),
    ('Radial D16mm P7.5mm', 'Radial D16mm P7.5mm'), ('Radial D16mm P7.5mm', 'CP_Radial_D16.0mm_P7.50mm'),
    ('Radial D18mm P7.5mm', 'Radial D18mm P7.5mm'), ('Radial D18mm P7.5mm', 'CP_Radial_D18.0mm_P7.50mm'),

    -- diodes
    ('DO-35', 'DO-204AH'), ('DO-35', 'SOD27'),
    ('DO-41', 'DO-204AL'),
    ('DO-15', 'DO-15'), ('DO-15', 'DO-204AC'),
    ('DO-27', 'DO-27'),
    ('DO-201AD', 'DO-201'),
    ('SOD-57', 'SOD-57'), ('SOD-64', 'SOD-64'), ('SOD-81', 'SOD-81'), ('SOD-83', 'SOD-83'),
    ('MiniMELF', 'DO-213AA'), ('MELF', 'DO-213AB'),
    ('SOD-128', 'SOD-128'), ('SOD-882', 'SOD-882'), ('SOD-882', 'DFN1006-2'),
    ('SOD-923', 'SOD-923'),
    ('PowerDI-123', 'PowerDI-123'), ('PowerDI-5', 'PowerDI-5'),

    -- bridge rectifiers
    ('KBP', 'KBP'), ('KBPC', 'KBPC'), ('KBU', 'KBU'), ('GBU', 'GBU'), ('GBJ', 'GBJ'),
    ('WOM', 'WOM'), ('DB', 'DB'), ('DB', 'DIP-4 Bridge'), ('MBS', 'MBS'), ('ABS', 'ABS'),

    -- small-signal SMD transistors
    ('SOT-23', 'SOT-23-3L'), ('SOT-23-5', 'SOT-23-5L'), ('SOT-23-6', 'SOT-23-6L'),
    ('SOT-23-8', 'SOT-23-8'),
    ('TSOT-23', 'TSOT-23'), ('TSOT-23-5', 'TSOT-23-5'), ('TSOT-23-6', 'TSOT-23-6'),
    ('TSOT-23-8', 'TSOT-23-8'),
    ('SC-59', 'SC-59'), ('SC-59', 'SOT-346'),
    ('SOT-143', 'SOT-143'), ('SOT-143', 'SOT-143B'), ('SOT-143', 'TO-253'),
    ('SOT-343', 'SOT-343'), ('SOT-343', 'SC-82'),
    ('SOT-416', 'SOT-416'), ('SOT-416', 'SC-75'),
    ('SOT-666', 'SOT-666'), ('SOT-723', 'SOT-723'), ('SOT-883', 'SOT-883'),
    ('SOT-89-5', 'SOT-89-5'),

    -- through-hole and power transistors
    ('TO-92', 'TO-226'), ('TO-92', 'TO-226AA'),
    ('TO-92L', 'TO-92L'),
    ('TO-18', 'TO-18'), ('TO-18', 'TO-206AA'),
    ('TO-46', 'TO-46'), ('TO-46', 'TO-206AB'),
    ('TO-72', 'TO-72'), ('TO-72', 'TO-206AF'),
    ('TO-106', 'TO-106'),
    ('TO-5', 'TO-5'), ('TO-5', 'TO-205AA'),
    ('TO-39', 'TO-39'), ('TO-39', 'TO-205AD'),
    ('TO-99', 'TO-99'), ('TO-99', 'TO-99-8'),
    ('TO-100', 'TO-100'), ('TO-100', 'TO-100-10'),
    ('TO-3', 'TO-3'), ('TO-3', 'TO-204'), ('TO-3', 'TO-204AA'),
    ('TO-66', 'TO-66'), ('TO-66', 'TO-213AA'),
    ('TO-64', 'TO-64'),
    ('TO-126', 'TO-126'), ('TO-126', 'TO-126-3'), ('TO-126', 'SOT-32'), ('TO-126', 'TO-225AA'),
    ('TO-202', 'TO-202'),
    ('TO-218', 'TO-218'), ('TO-218', 'SOT-93'),
    ('TO-220', 'TO-220-3L'), ('TO-220', 'TO-220C'),
    ('TO-220F', 'TO-220F'), ('TO-220F', 'TO-220FP'), ('TO-220F', 'ITO-220'),
    ('TO-220F', 'TO-220 Full Pack'),
    ('TO-220-5', 'TO-220-5'), ('TO-220-7', 'TO-220-7'),
    ('TO-3P', 'TO-3P'), ('TO-3P', 'TO-3PN'),
    ('TO-247', 'TO-247AC'), ('TO-247', 'TO-247AD'),
    ('TO-264', 'TO-264'),
    ('TO-251', 'TO-251'), ('TO-251', 'IPAK'),
    ('TO-262', 'TO-262'), ('TO-262', 'I2PAK'),
    ('TO-263-5', 'TO-263-5'), ('TO-263-7', 'TO-263-7'), ('TO-263-7', 'D2PAK-7'),
    ('TO-268', 'TO-268'), ('TO-268', 'D3PAK'),
    ('TO-277', 'TO-277'),
    ('LFPAK56', 'LFPAK56'), ('LFPAK56', 'SOT-669'),
    ('PowerPAK SO-8', 'PowerPAK SO-8'),
    ('Pentawatt', 'Pentawatt'), ('Pentawatt', 'Pentawatt-5'),
    ('Multiwatt-11', 'Multiwatt-11'), ('Multiwatt-15', 'Multiwatt-15'),

    -- single in-line
    ('SIP-3', 'SIP-3'), ('SIP-4', 'SIP-4'), ('SIP-5', 'SIP-5'), ('SIP-6', 'SIP-6'),
    ('SIP-7', 'SIP-7'), ('SIP-8', 'SIP-8'), ('SIP-9', 'SIP-9'), ('SIP-10', 'SIP-10'),
    ('SIP-12', 'SIP-12'),
    ('SIP-4', 'SIL-4'), ('SIP-6', 'SIL-6'), ('SIP-8', 'SIL-8'), ('SIP-9', 'SIL-9'),

    -- DIP: ceramic outlines take the plastic footprint; see the width note above
    ('DIP-4', 'DIP-4'), ('DIP-6', 'DIP-6'), ('DIP-6', 'PDIP-6'),
    ('DIP-8', 'CDIP-8'), ('DIP-14', 'CDIP-14'), ('DIP-16', 'CDIP-16'),
    ('DIP-18', 'CDIP-18'), ('DIP-20', 'CDIP-20'),
    ('DIP-22', 'DIP-22'), ('DIP-22', 'PDIP-22'), ('DIP-22', 'CDIP-22'),
    ('DIP-24', 'DIP-24'), ('DIP-24', 'PDIP-24'), ('DIP-24', 'DIP-24-300mil'), ('DIP-24', 'SPDIP-24'),
    ('DIP-24', 'SKDIP-24'),
    ('DIP-24W', 'DIP-24W'), ('DIP-24W', 'WDIP-24'), ('DIP-24W', 'WPDIP-24'), ('DIP-24W', 'CDIP-24'),
    ('DIP-24W', 'DIP-24-600mil'),
    ('DIP-28', 'DIP-28-300mil'), ('DIP-28', 'SPDIP-28'), ('DIP-28', 'SKDIP-28'),
    ('DIP-28W', 'DIP-28W'), ('DIP-28W', 'WDIP-28'), ('DIP-28W', 'WPDIP-28'), ('DIP-28W', 'CDIP-28'),
    ('DIP-28W', 'DIP-28-600mil'),
    ('DIP-32', 'DIP-32'), ('DIP-32', 'PDIP-32'), ('DIP-32', 'CDIP-32'), ('DIP-32', 'WDIP-32'),
    ('DIP-40', 'CDIP-40'), ('DIP-40', 'WDIP-40'), ('DIP-40', 'WPDIP-40'), ('DIP-40', 'DIP-40W'),
    ('DIP-42', 'DIP-42'), ('DIP-42', 'PDIP-42'),
    ('DIP-48', 'DIP-48'), ('DIP-48', 'PDIP-48'), ('DIP-48', 'CDIP-48'),
    ('DIP-64', 'DIP-64'), ('DIP-64', 'CDIP-64'),

    -- SOIC wide body (7.5 mm, 300 mil); SOIC-20 and up only exist wide
    ('SOIC-16W', 'SOIC-16W'), ('SOIC-16W', 'SOIC-16-300mil'), ('SOIC-16W', 'SO-16W'),
    ('SOIC-18', 'SOIC-18'), ('SOIC-18', 'SO-18'), ('SOIC-18', 'SOIC-18W'),
    ('SOIC-20', 'SOIC-20'), ('SOIC-20', 'SO-20'), ('SOIC-20', 'SOIC-20W'), ('SOIC-20', 'SOIC-20-300mil'),
    ('SOIC-24', 'SOIC-24'), ('SOIC-24', 'SO-24'), ('SOIC-24', 'SOIC-24W'), ('SOIC-24', 'SOIC-24-300mil'),
    ('SOIC-28', 'SOIC-28'), ('SOIC-28', 'SO-28'), ('SOIC-28', 'SOIC-28W'), ('SOIC-28', 'SOIC-28-300mil'),
    ('SOIC-8-EP', 'SOIC-8-EP'), ('SOIC-8-EP', 'SO-8-EP'), ('SOIC-8-EP', 'SOP-8-EP'),
    ('SOIC-8-EP', 'HSOIC-8'),

    -- shrink and thin small-outline
    ('SSOP-8', 'SSOP-8'), ('SSOP-10', 'SSOP-10'), ('SSOP-14', 'SSOP-14'), ('SSOP-16', 'SSOP-16'),
    ('SSOP-24', 'SSOP-24'), ('SSOP-48', 'SSOP-48'),
    ('QSOP-16', 'QSOP-16'), ('QSOP-20', 'QSOP-20'), ('QSOP-24', 'QSOP-24'),
    ('VSSOP-8', 'VSSOP-8'), ('VSSOP-10', 'VSSOP-10'),
    ('MSOP-8', 'MSOP-8-EP'), ('MSOP-10', 'MSOP-10-EP'), ('MSOP-12', 'MSOP-12'),
    ('TSSOP-38', 'TSSOP-38'), ('TSSOP-48', 'TSSOP-48'), ('TSSOP-56', 'TSSOP-56'),
    ('HTSSOP-14', 'HTSSOP-14'), ('HTSSOP-16', 'HTSSOP-16'), ('HTSSOP-20', 'HTSSOP-20'),
    ('HTSSOP-28', 'HTSSOP-28'),
    ('TSOP-I-48', 'TSOP-I-48'), ('TSOP-I-48', 'TSOP-48'),
    ('TSOP-II-44', 'TSOP-II-44'), ('TSOP-II-54', 'TSOP-II-54'),

    -- no-lead
    ('DFN-6', 'DFN-6'), ('DFN-10', 'DFN-10'), ('DFN-12', 'DFN-12'),
    ('WSON-6', 'WSON-6'), ('WSON-8', 'WSON-8'),
    ('QFN-16', 'QFN-16-EP'), ('QFN-20', 'QFN-20-EP'), ('QFN-24', 'QFN-24-EP'),
    ('QFN-32', 'QFN-32-EP'), ('QFN-48', 'QFN-48-EP'),
    ('QFN-28', 'QFN-28'), ('QFN-28', 'QFN-28-EP'),
    ('QFN-36', 'QFN-36'), ('QFN-36', 'QFN-36-EP'),
    ('QFN-40', 'QFN-40'), ('QFN-40', 'QFN-40-EP'),
    ('QFN-44', 'QFN-44'), ('QFN-44', 'QFN-44-EP'),
    ('QFN-56', 'QFN-56'), ('QFN-56', 'QFN-56-EP'),
    ('QFN-64', 'QFN-64'), ('QFN-64', 'QFN-64-EP'),

    -- quad flat and chip carriers
    ('TQFP-48', 'TQFP-48'), ('TQFP-80', 'TQFP-80'), ('TQFP-100', 'TQFP-100'),
    ('TQFP-144', 'TQFP-144'),
    ('LQFP-44', 'LQFP-44'), ('LQFP-52', 'LQFP-52'), ('LQFP-80', 'LQFP-80'),
    ('LQFP-128', 'LQFP-128'), ('LQFP-176', 'LQFP-176'), ('LQFP-208', 'LQFP-208'),
    ('PLCC-20', 'PLCC-20'), ('PLCC-28', 'PLCC-28'), ('PLCC-32', 'PLCC-32'),
    ('PLCC-44', 'PLCC-44'), ('PLCC-52', 'PLCC-52'), ('PLCC-68', 'PLCC-68'),
    ('PLCC-84', 'PLCC-84'),

    -- crystals
    ('HC-49', 'HC-49'), ('HC-49', 'HC-49/U'),
    ('HC-49S', 'HC-49S'), ('HC-49S', 'HC-49/S'), ('HC-49S', 'HC-49/US'), ('HC-49S', 'HC-49US'),
    ('HC-49 SMD', 'HC-49 SMD'), ('HC-49 SMD', 'HC-49/S SMD'), ('HC-49 SMD', 'HC-49SM'),
    ('DT-26', 'DT-26'), ('DT-26', 'Crystal 2x6mm'),
    ('DT-38', 'DT-38'), ('DT-38', 'Crystal 3x8mm'),
    ('SMD-2016-4P', 'SMD-2016-4P'), ('SMD-2016-4P', 'Crystal_SMD_2016-4Pin_2.0x1.6mm'),
    ('SMD-2520-4P', 'SMD-2520-4P'), ('SMD-2520-4P', 'Crystal_SMD_2520-4Pin_2.5x2.0mm'),
    ('SMD-3215-2P', 'SMD-3215-2P'), ('SMD-3215-2P', 'Crystal_SMD_3215-2Pin_3.2x1.5mm'),
    ('SMD-3225-4P', 'SMD-3225-4P'), ('SMD-3225-4P', 'Crystal_SMD_3225-4Pin_3.2x2.5mm'),
    ('SMD-5032-2P', 'SMD-5032-2P'), ('SMD-5032-2P', '5032-2P'),
    ('SMD-5032-2P', 'Crystal_SMD_5032-2Pin_5.0x3.2mm'),
    ('SMD-5032-4P', 'SMD-5032-4P'), ('SMD-5032-4P', 'Crystal_SMD_5032-4Pin_5.0x3.2mm'),
    ('SMD-7050-4P', 'SMD-7050-4P'), ('SMD-7050-4P', 'Crystal_SMD_7050-4Pin_7.0x5.0mm'),

    -- trimmers
    ('3296W', '3296W'), ('3296W', 'Bourns 3296W'), ('3296W', 'Potentiometer_Bourns_3296W_Vertical'),
    ('RM-065', 'RM-065'),

    -- valve sockets, by IEC base designation
    ('B7G', 'B7G'), ('B7G', 'Miniature 7-pin'),
    ('B8A', 'B8A'), ('B8A', 'Rimlock'),
    ('B8G', 'B8G'), ('B8G', 'Loctal'),
    ('B9A', 'B9A'), ('B9A', 'Noval'),
    ('B9D', 'B9D'), ('B9D', 'Magnoval'),
    ('K8A', 'K8A'), ('K8A', 'Octal');

CREATE TEMPORARY TABLE std_description (canonical VARCHAR(64), description TEXT);

INSERT INTO std_description (canonical, description) VALUES
    ('DIP-24', '300 mil row spacing; the 600 mil wide version is DIP-24W'),
    ('DIP-24W', 'Wide DIP, 600 mil row spacing'),
    ('DIP-28', '300 mil row spacing; the 600 mil wide version is DIP-28W'),
    ('DIP-28W', 'Wide DIP, 600 mil row spacing'),
    ('DIP-32', '600 mil row spacing'),
    ('DIP-40', '600 mil row spacing'),
    ('DIP-42', '600 mil row spacing'),
    ('DIP-48', '600 mil row spacing'),
    ('DIP-64', '900 mil row spacing'),
    ('SOIC-16W', 'Wide body, 7.5 mm (300 mil); SOIC-16 is the 3.9 mm body'),
    ('SOIC-18', 'Wide body, 7.5 mm (300 mil)'),
    ('SOIC-20', 'Wide body, 7.5 mm (300 mil)'),
    ('SOIC-24', 'Wide body, 7.5 mm (300 mil)'),
    ('SOIC-28', 'Wide body, 7.5 mm (300 mil)'),
    ('TO-5', 'Metal can, 8.9 mm, long leads'),
    ('TO-39', 'Metal can, 8.9 mm, short leads'),
    ('TO-18', 'Metal can, 4.8 mm, 3 leads'),
    ('TO-46', 'Metal can, 4.8 mm, short cap'),
    ('TO-72', 'Metal can, 4.8 mm, 4 leads'),
    ('TO-99', 'Metal can, 8 leads'),
    ('TO-100', 'Metal can, 10 leads'),
    ('TO-3', 'Metal power case, 2 leads'),
    ('TO-66', 'Metal power case, 2 leads, smaller than TO-3'),
    ('TO-220F', 'Fully insulated TO-220'),
    ('DIN 0204', 'Axial resistor body 3.6 x 1.6 mm (1/8 W)'),
    ('DIN 0207', 'Axial resistor body 6.3 x 2.5 mm (1/4 W)'),
    ('DIN 0309', 'Axial resistor body 9 x 3.2 mm (1/2 W)'),
    ('DIN 0411', 'Axial resistor body 9.9 x 3.6 mm (1 W)'),
    ('DIN 0414', 'Axial resistor body 11.9 x 4.5 mm (2 W)'),
    ('DIN 0617', 'Axial resistor body 17 x 6 mm (2-3 W)'),
    ('DIN 0918', 'Axial resistor body 18 x 9 mm (4-5 W)'),
    ('EIA-3216-18', 'Tantalum case A'),
    ('EIA-3528-21', 'Tantalum case B'),
    ('EIA-6032-28', 'Tantalum case C'),
    ('EIA-7343-31', 'Tantalum case D'),
    ('3296W', 'Multi-turn trimmer, 9.5 x 4.8 mm, top adjust'),
    ('RM-065', 'Single-turn trimmer, 7.3 x 6.4 mm'),
    ('B7G', 'Valve socket, 7-pin miniature'),
    ('B8A', 'Valve socket, Rimlock'),
    ('B8G', 'Valve socket, Loctal'),
    ('B9A', 'Valve socket, Noval'),
    ('B9D', 'Valve socket, Magnoval'),
    ('K8A', 'Valve socket, International Octal');

-- The match key: lower case, letters and digits only. FootprintService.matchKey is the Java side.
CREATE FUNCTION pg_temp.fp_key(s TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS
$$ SELECT coalesce(nullif(lower(regexp_replace(s, '[^A-Za-z0-9]', '', 'g')), ''), s) $$;

-- Not a package at all. FootprintService.isPackageName is the Java side.
CREATE FUNCTION pg_temp.fp_junk(s TEXT) RETURNS BOOLEAN LANGUAGE sql IMMUTABLE AS
$$ SELECT s ~ '[\u3000-\u303F\u3040-\u30FF\u3400-\u9FFF\uAC00-\uD7AF\uF900-\uFAFF\uFF00-\uFFEF]'
       OR s ~* '^\s*SMD\s*,'
       OR lower(regexp_replace(s, '[^A-Za-z0-9]', '', 'g')) IN
          ('', 'smd', 'smt', 'tht', 'throughhole', 'thruhole', 'plugin', 'other', 'others',
           'na', 'none', 'unknown', 'custom') $$;

-- 1. Every standard footprint exists in every organisation, under its exact name.
INSERT INTO footprint (organisation_id, name)
SELECT o.id, s.canonical
FROM organisation o
CROSS JOIN (SELECT DISTINCT canonical FROM std_footprint) s
WHERE NOT EXISTS (SELECT 1 FROM footprint f WHERE f.organisation_id = o.id AND f.name = s.canonical);

UPDATE footprint f
SET description = d.description
FROM std_description d
WHERE f.name = d.canonical AND f.description IS NULL;

-- What sat on DIP-24 or DIP-28 is the wide one (see the width note above).
UPDATE part SET footprint = footprint || 'W', updated_at = now()
WHERE footprint IN ('DIP-24', 'DIP-28');

-- 2. The standard aliases, where nothing in the organisation already claims the key. A spelling
-- in use that one of them names is merged in the next step, so its own name does not count.
INSERT INTO footprint_alias (footprint_id, organisation_id, alias)
SELECT DISTINCT ON (f.organisation_id, pg_temp.fp_key(s.alias)) f.id, f.organisation_id, s.alias
FROM std_footprint s
JOIN footprint f ON f.name = s.canonical
WHERE pg_temp.fp_key(s.alias) <> pg_temp.fp_key(s.canonical)
  AND NOT EXISTS (SELECT 1 FROM footprint_alias a
                  WHERE a.organisation_id = f.organisation_id
                    AND pg_temp.fp_key(a.alias) = pg_temp.fp_key(s.alias))
  AND NOT EXISTS (SELECT 1 FROM footprint o
                  JOIN std_footprint own ON own.canonical = o.name
                  WHERE o.organisation_id = f.organisation_id AND o.id <> f.id
                    AND pg_temp.fp_key(o.name) = pg_temp.fp_key(s.alias))
ORDER BY f.organisation_id, pg_temp.fp_key(s.alias), s.alias;

-- 3. Which footprint merges into which: one of the target's aliases names it, or it differs from a
-- standard name only in case and punctuation, or the part of it before a trailing "(...)" names
-- the target. A standard footprint is never folded into another one.
CREATE TEMPORARY TABLE footprint_merge AS
SELECT DISTINCT ON (c.src_id) c.src_id, c.organisation_id, c.src_name, c.dst_id, c.dst_name
FROM (
    SELECT src.id AS src_id, src.organisation_id, src.name AS src_name,
           dst.id AS dst_id, dst.name AS dst_name,
           CASE
               WHEN EXISTS (SELECT 1 FROM footprint_alias a
                            WHERE a.footprint_id = dst.id
                              AND pg_temp.fp_key(a.alias) = pg_temp.fp_key(src.name)) THEN 1
               WHEN pg_temp.fp_key(dst.name) = pg_temp.fp_key(src.name)
                    AND EXISTS (SELECT 1 FROM std_footprint own WHERE own.canonical = dst.name) THEN 2
               WHEN stripped.name <> src.name
                    AND (pg_temp.fp_key(dst.name) = pg_temp.fp_key(stripped.name)
                         OR EXISTS (SELECT 1 FROM footprint_alias a
                                    WHERE a.footprint_id = dst.id
                                      AND pg_temp.fp_key(a.alias) = pg_temp.fp_key(stripped.name))) THEN 3
           END AS rank
    FROM footprint src
    CROSS JOIN LATERAL (SELECT regexp_replace(src.name, '\s*\([^()]*\)\s*$', '') AS name) stripped
    JOIN footprint dst ON dst.organisation_id = src.organisation_id AND dst.id <> src.id
    WHERE NOT EXISTS (SELECT 1 FROM std_footprint own WHERE own.canonical = src.name)
) c
WHERE c.rank IS NOT NULL
ORDER BY c.src_id, c.rank;

UPDATE part p
SET footprint = m.dst_name, updated_at = now()
FROM footprint_merge m
WHERE p.organisation_id = m.organisation_id AND p.footprint = m.src_name;

UPDATE footprint_alias a
SET footprint_id = m.dst_id
FROM footprint_merge m
WHERE a.footprint_id = m.src_id;

DELETE FROM footprint f USING footprint_merge m WHERE f.id = m.src_id;

-- The merged name stays findable, unless the match key already says it.
INSERT INTO footprint_alias (footprint_id, organisation_id, alias)
SELECT m.dst_id, m.organisation_id, m.src_name
FROM footprint_merge m
WHERE pg_temp.fp_key(m.src_name) <> pg_temp.fp_key(m.dst_name)
  AND NOT EXISTS (SELECT 1 FROM footprint_alias a
                  WHERE a.organisation_id = m.organisation_id
                    AND pg_temp.fp_key(a.alias) = pg_temp.fp_key(m.src_name));

-- The component cache calls these two trimmer series "插件-3P,9.5x4.8mm" and
-- "插件,7.3x6.4mm", which step 4 clears; their series is their footprint.
UPDATE part SET footprint = '3296W', updated_at = now()
WHERE part_number ILIKE '3296W%' AND (footprint IS NULL OR pg_temp.fp_junk(footprint));
UPDATE part SET footprint = 'RM-065', updated_at = now()
WHERE part_number ILIKE 'RM065%' AND (footprint IS NULL OR pg_temp.fp_junk(footprint));

-- 4. What is not a package goes: off the parts, then off the list.
UPDATE part
SET footprint = NULL, updated_at = now()
WHERE footprint IS NOT NULL AND pg_temp.fp_junk(footprint);

DELETE FROM footprint WHERE pg_temp.fp_junk(name);

-- One spelling the rule cannot see: "804" on an 8-pin resistor network says no package anyone
-- could look up.
UPDATE part SET footprint = NULL, updated_at = now() WHERE footprint = '804';
DELETE FROM footprint WHERE name = '804';

DROP TABLE footprint_merge;
DROP TABLE std_description;
DROP TABLE std_footprint;
DROP FUNCTION pg_temp.fp_junk(TEXT);
DROP FUNCTION pg_temp.fp_key(TEXT);
