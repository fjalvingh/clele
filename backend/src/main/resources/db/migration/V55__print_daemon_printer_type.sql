-- A daemon now drives one of two printer families:
--
--   BROTHER_QL  a network printer: status over IPP on port 631, raster over raw TCP on port 9100.
--   DYMO_CUPS   a USB printer reached through the local CUPS queue, IPP for everything.
--
-- Every existing row is a Brother, hence the default. The database default is kept rather than
-- dropped because PrintDaemon is a Lombok @Builder entity, where a plain field initialiser is
-- ignored unless annotated @Builder.Default -- the default here is the belt to that braces.
ALTER TABLE print_daemon ADD COLUMN printer_type VARCHAR(20) NOT NULL DEFAULT 'BROTHER_QL';

-- CUPS destination name, e.g. "DYM0010". Null for a network printer, which uses printer_ip.
ALTER TABLE print_daemon ADD COLUMN printer_queue VARCHAR(128);

-- IPP media keyword chosen by the user, e.g. "custom_0.75x2in_0.75x2in". A LabelWriter cannot
-- sense which roll is loaded, so for that family the label size is configuration rather than
-- detection -- the one exception to the rule that media is detected and never configured.
-- Null for a Brother, which reports its own media.
ALTER TABLE print_daemon ADD COLUMN media_keyword VARCHAR(128);

-- printer-make-and-model as reported over IPP. Diagnostic aid shown beside the daemon.
ALTER TABLE print_daemon ADD COLUMN printer_model VARCHAR(128);

-- The area the printer can actually mark, reported by the daemon on every poll. Each driver knows
-- its own geometry (the Brother from constants measured on the hardware, the Dymo from the margins
-- CUPS reports), so the frontend can size a label from one reported pair of numbers instead of
-- mirroring per-printer constants it has no way to verify.
ALTER TABLE print_daemon ADD COLUMN printable_width_mm  NUMERIC;
ALTER TABLE print_daemon ADD COLUMN printable_length_mm NUMERIC;

-- Queues found on the daemon's machine and the label stock each offers, pushed by the daemon
-- whenever its printer target changes. Far too large for a poll header (dozens of media entries),
-- and it changes only when the machine's printer setup does.
ALTER TABLE print_daemon ADD COLUMN capabilities JSONB;
ALTER TABLE print_daemon ADD COLUMN capabilities_at TIMESTAMP;

-- Dymo stock is sized in inches, so a common roll is 19.05 x 50.8 mm. Whole millimetres cannot
-- express that and would put the label edge half a millimetre out.
ALTER TABLE print_daemon ALTER COLUMN media_width_mm  TYPE NUMERIC;
ALTER TABLE print_daemon ALTER COLUMN media_length_mm TYPE NUMERIC;
