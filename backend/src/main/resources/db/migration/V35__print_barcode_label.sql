-- Whether a label print also emits a second label carrying the part's Clele barcode (CLE-000123).
-- Remembered per user so the choice made in the print dialog sticks.
ALTER TABLE app_user ADD COLUMN print_barcode_label BOOLEAN NOT NULL DEFAULT false;
