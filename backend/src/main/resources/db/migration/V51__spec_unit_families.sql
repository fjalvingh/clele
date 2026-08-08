-- Assign unit families to the spec definitions -- step 2 of the typed-spec-value migration.
--
-- A family is what licenses parsing a value string into a number: knowing that capacitance is
-- measured in farads is what turns "100nF" into 1e-7. Without one, a field's values stay text.
--
-- WHY BY HAND. The name is not the family, and a regex over json_name gets real fields wrong:
-- naturalthermalresistance is degC/W and not resistance, inductancetolerance is a percentage and not
-- inductance, numberofresistors is a count. This is the same shape as V41's group taxonomy, and for
-- the same reason. It applies to every organisation (json_name is unique per organisation, so the
-- IN-lists match each organisation's own copy) and is re-runnable.
--
-- WHAT LICENSES THE ASSIGNMENT. Assigning a family asserts "a bare number in this field is in the
-- base SI unit". That was verified against the catalogue before writing this, not assumed -- the
-- Partsbox/Octopart import stored base units throughout:
--   accesstime        3.5e-12 .. 4e-5      seconds
--   capacitance       1e-12   .. 4.7e-4    farads
--   inputoffsetvoltage_vos_  2e-5 .. 0.02  volts (20 uV .. 20 mV)
--   propagationdelay  1.9e-9  .. 1e-5      seconds
--   resistance        0.0021  .. 3e8       ohms
--
-- WHAT IS DELIBERATELY LEFT NULL. An over-eager family is how a 4 KB memory becomes 4000, so a field
-- whose base unit is not certain gets none and keeps its values as text. Measured reasons:
--   memorysize (30..2e6), ramsize (16..384000), flashmemorysize (64000..1e6), density, eeprommemorysize
--                     -- mixed bits/bytes/KB, the data_size ambiguity
--   datarate (1..480) -- Mbit/s, NOT bit/s. A "data_rate" family based on bit/s would be wrong by 1e6
--   baudrate (1e7)    -- raw baud, inconsistent with datarate above
--   weight (0.002..113.4) -- grams, while the SI base for mass is the kilogram
--   life_hours_       -- hours, and a "time" family would render 2000 h as "2 ks"
--   gain, accuracy, resolution, linearity, dissipationfactor, speedgrade, integralnonlinearity_inl_,
--   casecode_imperial_, casecode_metric_  -- generic, unit-free or code-valued names carrying
--                     -- different quantities on different parts (a case code is not a measurement)
--   slewrate          -- V/us, no SI family, and the microsecond is part of the convention
--   operatingforce (1.27..15) -- probably newtons, but only 3 parts and gram-force is also used

-- --- Voltage --------------------------------------------------------------------------------
UPDATE spec_definition SET unit_family = 'voltage' WHERE json_name IN (
    'inputoffsetvoltage_vos_', 'receiverhysteresis', 'dropoutvoltage', 'operatingsupplyvoltage',
    'overvoltagethreshold', 'referencevoltage', 'undervoltagethreshold', 'voltage_output1',
    'voltage_output2', 'clampingvoltage', 'isolationvoltage', 'reversestandoffvoltage',
    'collectorbasevoltage_vcbo_', 'collectoremittersaturationvoltage', 'collectoremittervoltage_vceo_',
    'draintosourcevoltage_vdss_', 'emitterbasevoltage_vebo_', 'forwardvoltage',
    'gatetosourcevoltage_vgs_', 'reversebreakdownvoltage', 'reversevoltage', 'thresholdvoltage',
    'zenervoltage', 'coilvoltage_dc_', 'contactvoltagerating_ac_', 'maxi_ovoltage', 'voltage',
    'voltagerating');

-- --- Current --------------------------------------------------------------------------------
UPDATE spec_definition SET unit_family = 'current' WHERE json_name IN (
    'inputbiascurrent', 'darkcurrent', 'highleveloutputcurrent', 'lowleveloutputcurrent',
    'ripplecurrent', 'saturationcurrent', 'current_output1', 'current_output2', 'mincurrentlimit',
    'outputcurrentperchannel', 'quiescentcurrent', 'supplycurrent', 'peakpulsecurrent', 'tripcurrent',
    'averagerectifiedcurrent', 'continuousdraincurrent_id_', 'forwardcurrent', 'leakagecurrent',
    'maxcollectorcurrent', 'maxforwardsurgecurrent_ifsm_', 'zenercurrent', 'coilcurrent',
    'contactcurrentrating', 'current', 'dccurrent', 'fusecurrent', 'holdcurrent', 'rmscurrent_irms_',
    'testcurrent');

-- --- Resistance (RKM: 4k7, 100R) --------------------------------------------------------------
UPDATE spec_definition SET unit_family = 'resistance' WHERE json_name IN (
    'dcresistance_dcr_', 'esr_equivalentseriesresistance_', 'impedance', 'insulationresistance',
    'resistance', 'seriesresistance', 'draintosourceresistance', 'coilresistance',
    'contactresistance');

-- --- Capacitance (RKM: 2n2, 100n) -------------------------------------------------------------
UPDATE spec_definition SET unit_family = 'capacitance' WHERE json_name IN (
    'inputcapacitance', 'capacitance', 'loadcapacitance');

-- --- Inductance (RKM: 2u2, 4m7) ---------------------------------------------------------------
UPDATE spec_definition SET unit_family = 'inductance' WHERE json_name IN (
    'inductance');

-- --- Frequency ------------------------------------------------------------------------------
UPDATE spec_definition SET unit_family = 'frequency' WHERE json_name IN (
    'bandwidth', 'gainbandwidthproduct', 'selfresonantfrequency', 'testfrequency',
    'transitionfrequency');

-- --- Time -----------------------------------------------------------------------------------
UPDATE spec_definition SET unit_family = 'time' WHERE json_name IN (
    'settlingtime', 'propagationdelay', 'resettimeout', 'accesstime', 'timetotrip', 'recoverytime',
    'reverserecoverytime', 'falltime', 'operatetime', 'releasetime', 'responsetime', 'risetime',
    'turn_offdelaytime', 'turn_ondelaytime');

-- --- Power ----------------------------------------------------------------------------------
UPDATE spec_definition SET unit_family = 'power' WHERE json_name IN (
    'powerconsumption', 'powerperelement', 'powerrating', 'peakpulsepower', 'coilpower');

-- --- Length (already declared "m"; the family makes them parseable) ----------------------------
UPDATE spec_definition SET unit_family = 'length' WHERE json_name IN (
    'actuatorlength', 'contactmatinglength', 'contactpitch', 'depth', 'diameter', 'height',
    'height_seated_max_', 'leadlength', 'leadpitch', 'length', 'matingpostlength', 'pitch',
    'platingthickness', 'rowspacing', 'stackheight', 'switchtravel', 'terminalpitch', 'thickness',
    'width', 'dominantwavelength', 'peakwavelength');

-- --- Temperature and thermal resistance -------------------------------------------------------
UPDATE spec_definition SET unit_family = 'temperature' WHERE json_name IN (
    'maxjunctiontemperature');
-- degC/W. Named like a resistance, measured like nothing else in this list.
UPDATE spec_definition SET unit_family = 'thermal_resistance' WHERE json_name IN (
    'naturalthermalresistance');

-- --- Luminous intensity and angle -------------------------------------------------------------
UPDATE spec_definition SET unit_family = 'luminous_intensity' WHERE json_name IN (
    'luminousintensity');
UPDATE spec_definition SET unit_family = 'angle' WHERE json_name IN (
    'viewingangle');

-- --- Counts (scale-free: a bare number, never a prefix) ----------------------------------------
UPDATE spec_definition SET unit_family = 'count' WHERE json_name IN (
    'numberofamplifiers', 'numberofadcchannels', 'numberofbits', 'numberofbitsperelement',
    'numberofconverters', 'numberofdacchannels', 'numberofcircuits', 'numberofelements',
    'numberofbidirectionalchannels', 'numberofchannels', 'numberofdrivers', 'numberofi2cchannels',
    'numberofports', 'numberofpwmchannels', 'numberofreceivers', 'numberofspichannels',
    'numberoftransceivers', 'numberoftransmitters', 'numberofuartchannels',
    'numberofunidirectionalchannels', 'numberofusartchannels', 'numberofusbchannels',
    'numberofgates', 'numberofinputlines', 'numberofinputs', 'numberoflines', 'numberofmacrocells',
    'numberofoutputlines', 'numberofoutputs', 'numberofleds', 'numberofpins', 'numberofrows',
    'numberofterminals', 'packagequantity', 'numberofresistors', 'numberofturns',
    'numberofbatteries', 'numberofcells', 'numberofoutputphases', 'numberofregulators',
    'numberofvoltagesmonitored', 'addressbuswidth', 'databuswidth', 'numberofi_os',
    'numberofinterrupts', 'numberoftimers_counters', 'numberofwords', 'wordsize',
    'electricallife', 'life_cycles_', 'mechanicallife', 'numberofcontacts', 'numberofpoles',
    'numberofpositions');

-- --- Percentages and ratios -------------------------------------------------------------------
UPDATE spec_definition SET unit_family = 'percentage' WHERE json_name IN (
    'inductancetolerance', 'tolerance', 'efficiency', 'outputvoltageaccuracy', 'dutycycle',
    'voltagetolerance', 'currenttransferratio');
UPDATE spec_definition SET unit_family = 'ratio' WHERE json_name IN (
    'hfemin', 'qfactor');
UPDATE spec_definition SET unit_family = 'ppm' WHERE json_name IN (
    'temperaturecoefficient', 'frequencystability', 'frequencytolerance');
UPDATE spec_definition SET unit_family = 'decibel' WHERE json_name IN (
    'commonmoderejectionratio', 'insertionloss_db_', 'powersupplyrejectionratio_psrr_',
    'voltagegain');

-- Current transfer ratio is a percentage (measured 50..1600), but was declared in amperes with
-- metric scaling -- so it rendered "1.6 kA" for a 1600% opto-isolator. The family assignment above
-- is only half the fix; the display columns have to go too.
UPDATE spec_definition SET unit = NULL, metric_prefix = FALSE WHERE json_name = 'currenttransferratio';


-- --- TEXT definitions that hold measurements --------------------------------------------------
--
-- These are TEXT because their values are Partsbox *ranges* ("0..18", "null..125"), which no number
-- column could hold -- which is exactly why they were dead: convert-to-number has to refuse them and
-- no query can reach inside them. part_spec_value has value_min/value_max, so they become first-class.
-- This is where the bulk of the win is: 1,488 range values across the fields below, verified to be
-- in base units (frequency null..80000000 = 80 MHz, powerdissipation null..0.001 = 1 mW,
-- inputcurrent null..2.5e-7 = 250 nA).
--
-- Their data_type stays TEXT on purpose. A range is not a number, so NUMBER would be no more true
-- than TEXT is; data_type drives the edit widget, while the family drives storage. The few values
-- in these fields that are neither a range nor a parseable scalar ("-20°C to +70°C", "5V +/- 10%",
-- "-40.0 °C ~ 105.0 °C") simply stay text, which is the honest outcome.
UPDATE spec_definition SET unit_family = 'temperature' WHERE json_name IN (
    'operatingtemperature');
UPDATE spec_definition SET unit_family = 'voltage' WHERE json_name IN (
    'supplyvoltage', 'voltagerating_dc_', 'breakdownvoltage', 'dualsupplyvoltage', 'outputvoltage',
    'inputvoltage', 'reversevoltage_dc_', 'voltagerating_ac_', 'resetthresholdvoltage');
UPDATE spec_definition SET unit_family = 'current' WHERE json_name IN (
    'outputcurrent', 'currentrating', 'inputcurrent');
UPDATE spec_definition SET unit_family = 'power' WHERE json_name IN (
    'powerdissipation', 'outputpower');
UPDATE spec_definition SET unit_family = 'frequency' WHERE json_name IN (
    'frequency', 'switchingfrequency', 'outputfrequency');

-- The other TEXT fields are identifiers, dates, codes and enumerations -- case_package, scheduleB,
-- logicfunction, introdate, interface, ltb_date, ltd_date, outputtype, ratings, htscode, eccncode,
-- corearchitecture, ProjectedEOLDate, mfgpackage_id, hscode, peripherals, throwconfiguration,
-- flammabilityrating, yeol, adcchannelsresolution, family, sensortype, usbstandard, dielectric,
-- dacchannelsresolution -- and get no family. Neither does any SELECT definition: an enumeration
-- has values, not magnitudes.
