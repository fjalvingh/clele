-- Add a "Vintage & Minicomputer Systems" branch to the category taxonomy, with parallel
-- sub-trees for DEC PDP-11 and HP 21MX spare parts (CPU/memory/I-O modules, backplanes,
-- power, cabling, peripherals). Explicit ids in a fresh 1400 block (above the V8 1300 max);
-- the sequence is realigned afterwards.

INSERT INTO category (id, name, description, parent_id) VALUES
(1400, 'Vintage & Minicomputer Systems', 'Spare parts for classic minicomputer systems', NULL),

(1410, 'DEC PDP-11', 'DEC PDP-11 Unibus/Q-Bus minicomputer family', 1400),
(1411, 'PDP-11 CPU Modules', 'PDP-11 processor boards', 1410),
(1412, 'PDP-11 Memory Modules', 'PDP-11 core and semiconductor memory boards', 1410),
(1413, 'PDP-11 I/O & Peripheral Controllers', 'PDP-11 I/O interface and peripheral controller boards', 1410),
(1414, 'PDP-11 Backplanes & Bus Hardware', 'Unibus/Q-Bus backplanes, cards and bus hardware', 1410),
(1415, 'PDP-11 Power Supplies', 'PDP-11 system power supplies', 1410),
(1416, 'PDP-11 Cables & Connectors', 'PDP-11 internal and peripheral cabling', 1410),
(1417, 'PDP-11 Peripherals', 'PDP-11 terminals, tape/disk drives and front panels', 1410),

(1420, 'HP 21MX', 'Hewlett-Packard HP 21MX minicomputer family', 1400),
(1421, 'HP 21MX CPU Modules', 'HP 21MX processor boards', 1420),
(1422, 'HP 21MX Memory Modules', 'HP 21MX memory boards', 1420),
(1423, 'HP 21MX I/O & Peripheral Controllers', 'HP 21MX I/O interface and peripheral controller boards', 1420),
(1424, 'HP 21MX Backplanes & Bus Hardware', 'HP 21MX backplanes, cards and bus hardware', 1420),
(1425, 'HP 21MX Power Supplies', 'HP 21MX system power supplies', 1420),
(1426, 'HP 21MX Cables & Connectors', 'HP 21MX internal and peripheral cabling', 1420),
(1427, 'HP 21MX Peripherals', 'HP 21MX terminals, tape/disk drives and front panels', 1420);

SELECT setval('category_id_seq', (SELECT MAX(id) FROM category));
