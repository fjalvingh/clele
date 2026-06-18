-- Add a "Vacuum Tubes / Valves" branch to the category taxonomy. The inventory contains many
-- thermionic valves (triodes, pentodes, triode-pentode/hexode/heptode combinations, damper/
-- flyback diodes) and display tubes (CRT, Nixie, magic-eye) that have no home in the
-- semiconductor-oriented V7 tree. Explicit ids in a fresh 1300 block (above the V7 1200 max);
-- the sequence is realigned afterwards.

INSERT INTO category (id, name, description, parent_id) VALUES
(1300, 'Vacuum Tubes / Valves', 'Thermionic valves and electron tubes', NULL),
(1310, 'Triodes', 'Single and double triode valves', 1300),
(1320, 'Tetrodes & Pentodes', 'Tetrode, pentode and beam-power amplifying valves', 1300),
(1330, 'Combination / Multi-Section Tubes', 'Multi-section valves: triode-pentode, triode-hexode/heptode, diode-pentode, etc.', 1300),
(1340, 'Rectifier & Damper Tubes', 'Rectifier, damper, booster and flyback diode valves', 1300),
(1350, 'Display & Indicator Tubes', 'CRTs, Nixie tubes and magic-eye/indicator tubes', 1300),
(1360, 'Other / Special-Purpose Tubes', 'Mixer, converter and other special-purpose valves', 1300);

SELECT setval('category_id_seq', (SELECT MAX(id) FROM category));
