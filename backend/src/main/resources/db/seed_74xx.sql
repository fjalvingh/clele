-- Seed: 74xx TTL Logic IC series
-- Categories
INSERT INTO category (name, description, parent_id) VALUES
    ('Logic ICs', 'Integrated circuits implementing digital logic functions', NULL);

INSERT INTO category (name, description, parent_id)
    SELECT 'TTL / 74xx Series', 'Transistor-Transistor Logic — the classic 74xx family and variants (LS, ALS, HC, HCT…)', id FROM category WHERE name = 'Logic ICs';

INSERT INTO category (name, description, parent_id)
    SELECT 'Gates', 'Basic logic gates: NAND, NOR, AND, OR, XOR, NOT', id FROM category WHERE name = 'TTL / 74xx Series';
INSERT INTO category (name, description, parent_id)
    SELECT 'Flip-Flops & Latches', 'Bistable storage elements: D, JK, SR flip-flops and transparent latches', id FROM category WHERE name = 'TTL / 74xx Series';
INSERT INTO category (name, description, parent_id)
    SELECT 'Counters', 'Binary and BCD ripple and synchronous counters', id FROM category WHERE name = 'TTL / 74xx Series';
INSERT INTO category (name, description, parent_id)
    SELECT 'Shift Registers', 'Serial and parallel shift registers', id FROM category WHERE name = 'TTL / 74xx Series';
INSERT INTO category (name, description, parent_id)
    SELECT 'Buffers & Drivers', 'Line drivers, bus buffers, tri-state and open-collector drivers', id FROM category WHERE name = 'TTL / 74xx Series';
INSERT INTO category (name, description, parent_id)
    SELECT 'Multiplexers', 'Data selectors / multiplexers and demultiplexers', id FROM category WHERE name = 'TTL / 74xx Series';
INSERT INTO category (name, description, parent_id)
    SELECT 'Decoders & Encoders', 'BCD decoders, 7-segment drivers, priority encoders', id FROM category WHERE name = 'TTL / 74xx Series';
INSERT INTO category (name, description, parent_id)
    SELECT 'Arithmetic', 'Adders, comparators, ALUs and carry generators', id FROM category WHERE name = 'TTL / 74xx Series';

-- Helper: look up category ids by name
-- Gates
INSERT INTO part (part_number, name, description, manufacturer, datasheet_url, specs, category_id) VALUES
('7400', '7400 – Quad 2-input NAND Gate',
 'Four independent 2-input NAND gates in a single package.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7400.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","gates":4,"inputs_per_gate":2,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Gates')),

('7401', '7401 – Quad 2-input NAND Gate (Open Collector)',
 'Four 2-input NAND gates with open-collector outputs.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7401.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","gates":4,"inputs_per_gate":2,"output_type":"open_collector","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Gates')),

('7402', '7402 – Quad 2-input NOR Gate',
 'Four independent 2-input NOR gates.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7402.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","gates":4,"inputs_per_gate":2,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Gates')),

('7404', '7404 – Hex Inverter',
 'Six independent inverter (NOT) gates.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7404.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","gates":6,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Gates')),

('7405', '7405 – Hex Inverter (Open Collector)',
 'Six inverters with open-collector outputs.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7405.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","gates":6,"output_type":"open_collector","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Gates')),

('7406', '7406 – Hex Inverter/Buffer (Open Collector, 30V)',
 'Six high-voltage open-collector inverter buffers; outputs rated to 30V.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7406.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","gates":6,"output_voltage_max":"30V","output_type":"open_collector","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Gates')),

('7407', '7407 – Hex Buffer (Open Collector, 30V)',
 'Six high-voltage open-collector non-inverting buffers; outputs rated to 30V.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7407.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","gates":6,"output_voltage_max":"30V","output_type":"open_collector","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Gates')),

('7408', '7408 – Quad 2-input AND Gate',
 'Four independent 2-input AND gates.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7408.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","gates":4,"inputs_per_gate":2,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Gates')),

('7409', '7409 – Quad 2-input AND Gate (Open Collector)',
 'Four 2-input AND gates with open-collector outputs.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7409.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","gates":4,"inputs_per_gate":2,"output_type":"open_collector","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Gates')),

('7410', '7410 – Triple 3-input NAND Gate',
 'Three independent 3-input NAND gates.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7410.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","gates":3,"inputs_per_gate":3,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Gates')),

('7411', '7411 – Triple 3-input AND Gate',
 'Three independent 3-input AND gates.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7411.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","gates":3,"inputs_per_gate":3,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Gates')),

('7413', '7413 – Dual 4-input NAND Schmitt Trigger',
 'Two 4-input NAND gates with Schmitt trigger inputs for noise immunity.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7413.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","gates":2,"inputs_per_gate":4,"schmitt_trigger":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Gates')),

('7414', '7414 – Hex Schmitt-Trigger Inverter',
 'Six Schmitt-trigger inverters with hysteresis for clean switching from noisy inputs.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7414.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","gates":6,"schmitt_trigger":true,"hysteresis":"0.8V","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Gates')),

('7420', '7420 – Dual 4-input NAND Gate',
 'Two independent 4-input NAND gates.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7420.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","gates":2,"inputs_per_gate":4,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Gates')),

('7421', '7421 – Dual 4-input AND Gate',
 'Two independent 4-input AND gates.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7421.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","gates":2,"inputs_per_gate":4,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Gates')),

('7427', '7427 – Triple 3-input NOR Gate',
 'Three independent 3-input NOR gates.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7427.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","gates":3,"inputs_per_gate":3,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Gates')),

('7430', '7430 – 8-input NAND Gate',
 'Single 8-input NAND gate.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7430.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","gates":1,"inputs_per_gate":8,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Gates')),

('7432', '7432 – Quad 2-input OR Gate',
 'Four independent 2-input OR gates.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7432.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","gates":4,"inputs_per_gate":2,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Gates')),

('7486', '7486 – Quad 2-input XOR Gate',
 'Four independent 2-input exclusive-OR gates.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7486.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","gates":4,"inputs_per_gate":2,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Gates')),

('74266', '74266 – Quad 2-input XNOR Gate (Open Collector)',
 'Four exclusive-NOR gates with open-collector outputs.',
 'Texas Instruments', NULL,
 '{"supply_voltage":"5V","package":"DIP-14","gates":4,"inputs_per_gate":2,"output_type":"open_collector","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Gates'));

-- Flip-Flops & Latches
INSERT INTO part (part_number, name, description, manufacturer, datasheet_url, specs, category_id) VALUES
('7473', '7473 – Dual JK Flip-Flop (with Clear)',
 'Two negative-edge-triggered JK flip-flops with active-low asynchronous clear.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7473.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","flip_flops":2,"type":"JK","trigger":"negative_edge","clear":true,"preset":false,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Flip-Flops & Latches')),

('7474', '7474 – Dual D-type Positive-Edge-Triggered Flip-Flop',
 'Two D-type flip-flops with individual preset, clear and clock inputs.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7474.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","flip_flops":2,"type":"D","trigger":"positive_edge","clear":true,"preset":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Flip-Flops & Latches')),

('7475', '7475 – 4-bit Bistable Latch',
 'Four transparent D-latches with complementary outputs; latched when LE is low.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7475.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","bits":4,"type":"transparent_latch","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Flip-Flops & Latches')),

('7476', '7476 – Dual JK Flip-Flop (with Preset and Clear)',
 'Two negative-edge-triggered JK flip-flops with asynchronous preset and clear.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7476.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","flip_flops":2,"type":"JK","trigger":"negative_edge","clear":true,"preset":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Flip-Flops & Latches')),

('74107', '74107 – Dual JK Flip-Flop (with Clear)',
 'Two JK master-slave flip-flops with active-low clear.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74107.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","flip_flops":2,"type":"JK","clear":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Flip-Flops & Latches')),

('74109', '74109 – Dual JK Positive-Edge-Triggered Flip-Flop',
 'Two positive-edge-triggered JK flip-flops with preset and clear.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74109.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","flip_flops":2,"type":"JK","trigger":"positive_edge","clear":true,"preset":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Flip-Flops & Latches')),

('74112', '74112 – Dual JK Negative-Edge-Triggered Flip-Flop',
 'Two negative-edge-triggered JK flip-flops with preset and clear.',
 'Texas Instruments', NULL,
 '{"supply_voltage":"5V","package":"DIP-16","flip_flops":2,"type":"JK","trigger":"negative_edge","clear":true,"preset":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Flip-Flops & Latches')),

('74174', '74174 – Hex D-type Flip-Flop (with Clear)',
 'Six D-type positive-edge-triggered flip-flops sharing clock and active-low master reset.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74174.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","flip_flops":6,"type":"D","trigger":"positive_edge","clear":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Flip-Flops & Latches')),

('74175', '74175 – Quad D-type Flip-Flop (with Clear)',
 'Four D-type positive-edge-triggered flip-flops with complementary outputs and common clear.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74175.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","flip_flops":4,"type":"D","trigger":"positive_edge","clear":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Flip-Flops & Latches')),

('74273', '74273 – Octal D-type Flip-Flop (with Clear)',
 'Eight D-type positive-edge-triggered flip-flops sharing clock and reset.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74273.pdf',
 '{"supply_voltage":"5V","package":"DIP-20","flip_flops":8,"type":"D","trigger":"positive_edge","clear":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Flip-Flops & Latches')),

('74373', '74373 – Octal D-type Transparent Latch (Tri-State)',
 'Eight transparent D-latches with common latch enable and tri-state outputs.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74373.pdf',
 '{"supply_voltage":"5V","package":"DIP-20","bits":8,"type":"transparent_latch","output_type":"tri_state","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Flip-Flops & Latches')),

('74374', '74374 – Octal D-type Flip-Flop (Tri-State)',
 'Eight positive-edge-triggered D flip-flops with tri-state outputs; commonly used as bus interface register.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74374.pdf',
 '{"supply_voltage":"5V","package":"DIP-20","flip_flops":8,"type":"D","trigger":"positive_edge","output_type":"tri_state","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Flip-Flops & Latches')),

('74573', '74573 – Octal D-type Transparent Latch (Tri-State)',
 'Eight transparent latches with tri-state outputs; pin-compatible with 74373 but with non-inverting control.',
 'Texas Instruments', NULL,
 '{"supply_voltage":"5V","package":"DIP-20","bits":8,"type":"transparent_latch","output_type":"tri_state","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Flip-Flops & Latches')),

('74574', '74574 – Octal D-type Flip-Flop (Tri-State)',
 'Eight positive-edge-triggered flip-flops with tri-state outputs; pin-compatible with 74374.',
 'Texas Instruments', NULL,
 '{"supply_voltage":"5V","package":"DIP-20","flip_flops":8,"type":"D","trigger":"positive_edge","output_type":"tri_state","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Flip-Flops & Latches'));

-- Counters
INSERT INTO part (part_number, name, description, manufacturer, datasheet_url, specs, category_id) VALUES
('7490', '7490 – Decade Counter',
 'Asynchronous BCD decade counter with separate divide-by-2 and divide-by-5 sections.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7490a.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","type":"decade","modulo":10,"ripple":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Counters')),

('7492', '7492 – Divide-by-12 Ripple Counter',
 'Divide-by-2 and divide-by-6 sections; cascadable to divide-by-12.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7492a.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","type":"binary","modulo":12,"ripple":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Counters')),

('7493', '7493 – 4-bit Binary Ripple Counter',
 'Asynchronous 4-bit binary counter (divide-by-2 plus divide-by-8 sections).',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7493a.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","bits":4,"type":"binary","modulo":16,"ripple":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Counters')),

('74160', '74160 – 4-bit Synchronous Decade Counter (Async Clear)',
 'Synchronous BCD counter with asynchronous clear, synchronous load and count-enable inputs.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74160a.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","bits":4,"type":"decade","modulo":10,"synchronous":true,"clear":"async","load":"sync","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Counters')),

('74161', '74161 – 4-bit Synchronous Binary Counter (Async Clear)',
 'Synchronous 4-bit binary counter with asynchronous clear and synchronous load.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74161a.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","bits":4,"type":"binary","modulo":16,"synchronous":true,"clear":"async","load":"sync","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Counters')),

('74162', '74162 – 4-bit Synchronous Decade Counter (Sync Clear)',
 'Synchronous BCD counter with synchronous clear and synchronous load.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74162a.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","bits":4,"type":"decade","modulo":10,"synchronous":true,"clear":"sync","load":"sync","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Counters')),

('74163', '74163 – 4-bit Synchronous Binary Counter (Sync Clear)',
 'Synchronous 4-bit binary counter with synchronous clear and load; very widely used.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74163a.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","bits":4,"type":"binary","modulo":16,"synchronous":true,"clear":"sync","load":"sync","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Counters')),

('74190', '74190 – 4-bit Synchronous Up/Down Decade Counter',
 'Presettable BCD up/down counter with ripple carry output.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74190.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","bits":4,"type":"decade","up_down":true,"synchronous":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Counters')),

('74191', '74191 – 4-bit Synchronous Up/Down Binary Counter',
 'Presettable 4-bit binary up/down counter with ripple carry output.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74191.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","bits":4,"type":"binary","up_down":true,"synchronous":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Counters')),

('74192', '74192 – 4-bit Synchronous Up/Down Decade Counter (Separate Clocks)',
 'Presettable BCD up/down counter with separate up/down clocks and asynchronous clear.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74192.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","bits":4,"type":"decade","up_down":true,"separate_clocks":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Counters')),

('74193', '74193 – 4-bit Synchronous Up/Down Binary Counter (Separate Clocks)',
 'Presettable 4-bit binary up/down counter with separate up/down clocks and asynchronous clear.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74193.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","bits":4,"type":"binary","up_down":true,"separate_clocks":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Counters')),

('74393', '74393 – Dual 4-bit Binary Ripple Counter',
 'Two independent 4-bit binary ripple counters in one package.',
 'Texas Instruments', NULL,
 '{"supply_voltage":"5V","package":"DIP-14","bits":4,"counters":2,"type":"binary","ripple":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Counters'));

-- Shift Registers
INSERT INTO part (part_number, name, description, manufacturer, datasheet_url, specs, category_id) VALUES
('7491', '7491 – 8-bit Serial-In / Serial-Out Shift Register',
 'Eight-stage serial shift register with complementary outputs from the last stage.',
 'Texas Instruments', NULL,
 '{"supply_voltage":"5V","package":"DIP-14","bits":8,"type":"SISO","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Shift Registers')),

('7495', '7495 – 4-bit Parallel-In / Parallel-Out Shift Register',
 'Four-bit shift register with parallel input, parallel output and right/left shift control.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7495a.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","bits":4,"type":"PIPO","bidirectional":false,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Shift Registers')),

('74164', '74164 – 8-bit Serial-In / Parallel-Out Shift Register',
 'Eight-bit serial-to-parallel shift register with asynchronous clear.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74164.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","bits":8,"type":"SIPO","clear":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Shift Registers')),

('74165', '74165 – 8-bit Parallel-In / Serial-Out Shift Register',
 'Eight-bit parallel-load shift register with complementary serial outputs.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74165.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","bits":8,"type":"PISO","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Shift Registers')),

('74166', '74166 – 8-bit Parallel-In / Serial-Out Shift Register (Clock Enable)',
 'Eight-bit PISO shift register with clock enable and asynchronous clear.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74166.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","bits":8,"type":"PISO","clock_enable":true,"clear":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Shift Registers')),

('74194', '74194 – 4-bit Bidirectional Universal Shift Register',
 'Four-bit bidirectional shift register with parallel load; mode pins select shift-left, shift-right, hold or load.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74194a.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","bits":4,"type":"PIPO","bidirectional":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Shift Registers')),

('74195', '74195 – 4-bit Parallel-Access Shift Register',
 'Four-bit PIPO shift register with JK serial input for the first stage.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74195.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","bits":4,"type":"PIPO","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Shift Registers')),

('74198', '74198 – 8-bit Bidirectional Shift Register',
 'Eight-bit bidirectional universal shift register with parallel load and clear.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74198.pdf',
 '{"supply_voltage":"5V","package":"DIP-24","bits":8,"type":"PIPO","bidirectional":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Shift Registers')),

('74595', '74595 – 8-bit Serial-In / Parallel-Out Shift Register (with Output Latch)',
 'Eight-bit shift register feeding an 8-bit output latch; serial-in with tri-state parallel outputs.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74hc595.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","bits":8,"type":"SIPO","output_latch":true,"output_type":"tri_state","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Shift Registers'));

-- Buffers & Drivers
INSERT INTO part (part_number, name, description, manufacturer, datasheet_url, specs, category_id) VALUES
('7416', '7416 – Hex Inverter/Buffer (Open Collector, 15V)',
 'Six open-collector inverting buffers with 15V output rating.',
 'Texas Instruments', NULL,
 '{"supply_voltage":"5V","package":"DIP-14","gates":6,"output_voltage_max":"15V","output_type":"open_collector","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Buffers & Drivers')),

('7417', '7417 – Hex Buffer (Open Collector, 15V)',
 'Six non-inverting open-collector buffers with 15V output rating.',
 'Texas Instruments', NULL,
 '{"supply_voltage":"5V","package":"DIP-14","gates":6,"output_voltage_max":"15V","output_type":"open_collector","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Buffers & Drivers')),

('74125', '74125 – Quad Tri-State Buffer (Active-Low Enable)',
 'Four tri-state buffers; each independently enabled by its own active-low OE pin.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74125.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","buffers":4,"output_type":"tri_state","enable":"active_low","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Buffers & Drivers')),

('74126', '74126 – Quad Tri-State Buffer (Active-High Enable)',
 'Four tri-state buffers; each independently enabled by its own active-high OE pin.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74126.pdf',
 '{"supply_voltage":"5V","package":"DIP-14","buffers":4,"output_type":"tri_state","enable":"active_high","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Buffers & Drivers')),

('74240', '74240 – Octal Inverting Buffer/Line Driver (Tri-State)',
 'Eight inverting tri-state buffers in two groups of four with common enable.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74240.pdf',
 '{"supply_voltage":"5V","package":"DIP-20","buffers":8,"inverting":true,"output_type":"tri_state","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Buffers & Drivers')),

('74241', '74241 – Octal Non-Inverting Buffer/Line Driver (Tri-State)',
 'Eight non-inverting tri-state buffers in two groups of four.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74241.pdf',
 '{"supply_voltage":"5V","package":"DIP-20","buffers":8,"inverting":false,"output_type":"tri_state","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Buffers & Drivers')),

('74244', '74244 – Octal Non-Inverting Buffer/Line Driver (Tri-State)',
 'Eight tri-state non-inverting buffers; workhorse address/data bus driver.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74244.pdf',
 '{"supply_voltage":"5V","package":"DIP-20","buffers":8,"inverting":false,"output_type":"tri_state","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Buffers & Drivers')),

('74245', '74245 – Octal Bus Transceiver (Tri-State)',
 'Eight non-inverting bidirectional bus transceivers; direction controlled by DIR pin.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74245.pdf',
 '{"supply_voltage":"5V","package":"DIP-20","bits":8,"bidirectional":true,"output_type":"tri_state","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Buffers & Drivers'));

-- Multiplexers
INSERT INTO part (part_number, name, description, manufacturer, datasheet_url, specs, category_id) VALUES
('74150', '74150 – 16-input Multiplexer',
 'Single 16-to-1 data selector/multiplexer with inverted output.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74150.pdf',
 '{"supply_voltage":"5V","package":"DIP-24","inputs":16,"outputs":1,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Multiplexers')),

('74151', '74151 – 8-input Multiplexer',
 'Single 8-to-1 data selector/multiplexer with complementary outputs.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74151a.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","inputs":8,"outputs":1,"complementary_output":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Multiplexers')),

('74153', '74153 – Dual 4-input Multiplexer',
 'Two independent 4-to-1 data selectors sharing the same select inputs.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74153.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","mux_count":2,"inputs_per_mux":4,"shared_select":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Multiplexers')),

('74157', '74157 – Quad 2-input Multiplexer',
 'Four 2-to-1 multiplexers with common select and enable.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74157.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","mux_count":4,"inputs_per_mux":2,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Multiplexers')),

('74158', '74158 – Quad 2-input Multiplexer (Inverting)',
 'Four 2-to-1 multiplexers with inverted outputs; common select and enable.',
 'Texas Instruments', NULL,
 '{"supply_voltage":"5V","package":"DIP-16","mux_count":4,"inputs_per_mux":2,"inverting":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Multiplexers')),

('74251', '74251 – 8-input Multiplexer (Tri-State)',
 '8-to-1 mux with complementary tri-state outputs.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74251.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","inputs":8,"outputs":1,"output_type":"tri_state","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Multiplexers')),

('74257', '74257 – Quad 2-input Multiplexer (Tri-State)',
 'Four 2-to-1 multiplexers with tri-state outputs.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74257b.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","mux_count":4,"inputs_per_mux":2,"output_type":"tri_state","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Multiplexers'));

-- Decoders & Encoders
INSERT INTO part (part_number, name, description, manufacturer, datasheet_url, specs, category_id) VALUES
('7442', '7442 – BCD-to-Decimal Decoder',
 'Decodes a 4-bit BCD input to one of 10 active-low outputs.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7442a.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","inputs":4,"outputs":10,"active_level":"low","encoding":"BCD","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Decoders & Encoders')),

('7445', '7445 – BCD-to-Decimal Decoder/Driver (Open Collector)',
 'Open-collector BCD-to-decimal decoder; outputs withstand 80mA at up to 15V.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7445.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","inputs":4,"outputs":10,"output_type":"open_collector","output_voltage_max":"15V","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Decoders & Encoders')),

('7446', '7446 – BCD-to-7-Segment Decoder/Driver (Open Collector, 30V)',
 'Drives common-anode 7-segment displays; open-collector outputs rated to 30V/40mA.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7446a.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","inputs":4,"outputs":7,"output_type":"open_collector","output_voltage_max":"30V","display":"common_anode","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Decoders & Encoders')),

('7447', '7447 – BCD-to-7-Segment Decoder/Driver (Open Collector, 15V)',
 'Common-anode 7-segment driver; open-collector outputs to 15V/40mA.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7447a.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","inputs":4,"outputs":7,"output_type":"open_collector","output_voltage_max":"15V","display":"common_anode","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Decoders & Encoders')),

('7448', '7448 – BCD-to-7-Segment Decoder/Driver (Internal Pull-Ups)',
 'Common-cathode 7-segment driver with active-high totem-pole outputs.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7448.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","inputs":4,"outputs":7,"display":"common_cathode","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Decoders & Encoders')),

('74138', '74138 – 3-to-8 Line Decoder/Demultiplexer',
 'Decodes 3 binary inputs to one of 8 mutually exclusive active-low outputs; three enable inputs.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74138.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","inputs":3,"outputs":8,"active_level":"low","enable_inputs":3,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Decoders & Encoders')),

('74139', '74139 – Dual 2-to-4 Line Decoder/Demultiplexer',
 'Two independent 2-to-4 decoders each with active-low enable and outputs.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74139a.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","decoders":2,"inputs_per_decoder":2,"outputs_per_decoder":4,"active_level":"low","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Decoders & Encoders')),

('74147', '74147 – 10-to-4 Priority Encoder',
 'Encodes the highest-priority active-low input among 9 inputs to a 4-bit BCD code.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74147.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","inputs":9,"outputs":4,"encoding":"BCD","priority":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Decoders & Encoders')),

('74148', '74148 – 8-to-3 Priority Encoder',
 'Encodes the highest-priority of 8 active-low inputs to a 3-bit binary code.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74148.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","inputs":8,"outputs":3,"priority":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Decoders & Encoders')),

('74154', '74154 – 4-to-16 Line Decoder/Demultiplexer',
 'Decodes 4-bit binary to one of 16 active-low outputs.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74154.pdf',
 '{"supply_voltage":"5V","package":"DIP-24","inputs":4,"outputs":16,"active_level":"low","logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Decoders & Encoders'));

-- Arithmetic
INSERT INTO part (part_number, name, description, manufacturer, datasheet_url, specs, category_id) VALUES
('7483', '7483 – 4-bit Binary Full Adder (Fast Carry)',
 'Adds two 4-bit words plus carry-in; provides sum and carry-out. Cascade for wider adders.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7483a.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","bits":4,"carry_in":true,"carry_out":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Arithmetic')),

('7485', '7485 – 4-bit Magnitude Comparator',
 'Compares two 4-bit words; outputs A>B, A=B, A<B; cascadable for wider comparisons.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn7485.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","bits":4,"cascadable":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Arithmetic')),

('74181', '74181 – 4-bit Arithmetic Logic Unit (ALU)',
 'Performs 16 logic and 16 arithmetic operations on two 4-bit words; selectable active-high or active-low data.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74181.pdf',
 '{"supply_voltage":"5V","package":"DIP-24","bits":4,"operations":32,"carry_lookahead":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Arithmetic')),

('74182', '74182 – Look-Ahead Carry Generator',
 'Generates carry look-ahead signals for groups of 74181 ALUs to speed up wide additions.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74182.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","carry_groups":4,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Arithmetic')),

('74283', '74283 – 4-bit Binary Full Adder (Fast Carry)',
 'Functionally identical to 7483 but with a more convenient pinout for board layout.',
 'Texas Instruments', 'https://www.ti.com/lit/ds/symlink/sn74283.pdf',
 '{"supply_voltage":"5V","package":"DIP-16","bits":4,"carry_in":true,"carry_out":true,"logic_family":"TTL"}',
 (SELECT id FROM category WHERE name = 'Arithmetic'));
