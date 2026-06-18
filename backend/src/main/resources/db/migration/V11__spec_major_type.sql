-- Group each spec definition under a "major type" for display: DIMENSIONS, PHYSICAL or TECHNICAL.
-- New definitions default to TECHNICAL; the one-time classification below assigns the size
-- (DIMENSIONS) and package/mechanical/material (PHYSICAL) specs from the current catalogue.
-- Anything not listed stays TECHNICAL. These can be re-bucketed by hand in the Spec Definitions UI.

ALTER TABLE spec_definition
    ADD COLUMN major_type VARCHAR(20) NOT NULL DEFAULT 'TECHNICAL';

-- Size measurements.
UPDATE spec_definition SET major_type = 'DIMENSIONS' WHERE json_name IN (
    'actuatorlength', 'contactmatinglength', 'contactpitch', 'depth', 'diameter', 'height',
    'height_seated_max_', 'leadlength', 'leadpitch', 'length', 'matingpostlength', 'pitch',
    'platingthickness', 'rowspacing', 'stackheight', 'switchtravel', 'terminalpitch',
    'thickness', 'width'
);

-- Package / mechanical / material / appearance / pin & contact counts.
UPDATE spec_definition SET major_type = 'PHYSICAL' WHERE json_name IN (
    'case_package', 'casecode_imperial_', 'casecode_metric_', 'mfgpackage_id', 'packaging',
    'packagequantity', 'mount', 'fasteningtype', 'termination', 'numberofpins', 'numberofcontacts',
    'numberofpositions', 'numberofterminals', 'numberofrows', 'numberofpoles', 'throwconfiguration',
    'contactgender', 'gender', 'contactstyle', 'contactmaterial', 'contactplating', 'plating',
    'shellfinish', 'shellmaterial', 'terminalmaterial', 'bodymaterial', 'housingmaterial',
    'housingcolor', 'material', 'corematerial', 'composition', 'dielectricmaterial', 'insulation',
    'insulationcolor', 'lenscolor', 'lensstyle', 'lenstransparency', 'color', 'shape', 'orientation',
    'shielding', 'sealable', 'connectortype', 'weight'
);
