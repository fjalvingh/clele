-- A leaf location can be a drawer (or box, or tray) divided into a fixed number of slots, each
-- holding one kind of component. NULL = not divided into slots (the location just holds things).
ALTER TABLE location ADD COLUMN slot_count INTEGER;
ALTER TABLE location ADD CONSTRAINT location_slot_count_positive CHECK (slot_count > 0);
