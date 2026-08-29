-- A numeric spec value may now hold a nominal value AND bounds at the same time.
--
-- min/typ/max is how a datasheet states a parameter — "4.5 V, 5 V typical, 5.5 V" is one fact, not
-- two — but V50's one-shape check allowed a row to be a scalar or a range and never both. Entering
-- such a value meant throwing away either the typical value or the band around it, and there was no
-- way to enter a range at all from the UI.
--
-- Text stays exclusive with the numbers. It is the "nothing was extracted from this" shape, so a row
-- holding both would have two answers to the same question and no rule for which one wins.
--
-- The wire form of the combined shape is "min..nominal..max" (PartSpecValueService.valueOf), an open
-- bound written "null" exactly as the two-part form already writes it. The two-part "min..max" and
-- the bare number are unchanged, so every value stored before this migration reads back as it did.
ALTER TABLE part_spec_value DROP CONSTRAINT part_spec_value_one_shape;

ALTER TABLE part_spec_value ADD CONSTRAINT part_spec_value_one_shape CHECK (
    (value_text IS NOT NULL)::int
    + (num_nonnulls(value_num, value_min, value_max) > 0)::int = 1
);

COMMENT ON TABLE part_spec_value IS
    'Typed spec values in SI base units. Either value_text, or any combination of value_num (nominal) and value_min/value_max (bounds).';
