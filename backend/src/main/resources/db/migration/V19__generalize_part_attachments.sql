-- Generalize part_image into part_attachment: a single bytea table holding photos, datasheets,
-- and user-uploaded attachments, distinguished by `type`. Existing rows are all photos.
-- Rename in place so existing image data and the part_id FK (ON DELETE CASCADE) are preserved.

ALTER TABLE part_image RENAME TO part_attachment;
ALTER SEQUENCE part_image_id_seq RENAME TO part_attachment_id_seq;
ALTER INDEX idx_part_image_part_id RENAME TO idx_part_attachment_part_id;
ALTER TABLE part_attachment RENAME COLUMN image_data TO data;

-- New columns. Defaults backfill the existing photo rows (PNG, no original filename), then the
-- defaults are dropped so new rows must supply type/content_type explicitly.
ALTER TABLE part_attachment ADD COLUMN type         VARCHAR(20)  NOT NULL DEFAULT 'PHOTO';
ALTER TABLE part_attachment ADD COLUMN content_type VARCHAR(100) NOT NULL DEFAULT 'image/png';
ALTER TABLE part_attachment ADD COLUMN filename     VARCHAR(255);

ALTER TABLE part_attachment ALTER COLUMN type         DROP DEFAULT;
ALTER TABLE part_attachment ALTER COLUMN content_type DROP DEFAULT;
