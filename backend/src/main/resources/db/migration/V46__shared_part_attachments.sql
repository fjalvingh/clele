-- Attachments become shareable between parts.
--
-- Until now a part_attachment row belonged to exactly one part, so the same photo had to be stored
-- again for every part that needed it — every value in a resistor kit carrying its own copy of the
-- identical picture. The blob is therefore separated from the part it is shown on: part_attachment
-- keeps the content and part_attachment_link records which parts use it.
--
-- Two new fields come with that:
--   description  the part number of the very first part the attachment was used for. It never
--                changes afterwards, so a shared image keeps naming where it came from.
--   md5_hash     MD5 of the stored bytes, so an upload that is byte-identical to something already
--                held is recognised and linked instead of stored a second time.
--
-- part_attachment loses part_id/display_order (both move to the link) and gains organisation_id:
-- it used to derive its tenant through part_id, and with several parts that derivation no longer
-- holds. Sharing and hash matching stay strictly inside one organisation.

ALTER TABLE part_attachment ADD COLUMN description     VARCHAR(255);
ALTER TABLE part_attachment ADD COLUMN md5_hash        VARCHAR(32);
ALTER TABLE part_attachment ADD COLUMN organisation_id BIGINT REFERENCES organisation (id);

CREATE TABLE part_attachment_link (
    id            BIGSERIAL PRIMARY KEY,
    part_id       BIGINT    NOT NULL REFERENCES part (id) ON DELETE CASCADE,
    attachment_id BIGINT    NOT NULL REFERENCES part_attachment (id) ON DELETE CASCADE,
    display_order INTEGER   NOT NULL DEFAULT 0,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT part_attachment_link_unique UNIQUE (part_id, attachment_id)
);

-- Every existing attachment belongs to exactly one part: that pairing becomes its first link, and
-- that part supplies both the description and the organisation.
INSERT INTO part_attachment_link (part_id, attachment_id, display_order, created_at)
SELECT part_id, id, display_order, created_at
FROM part_attachment;

UPDATE part_attachment a
SET description     = p.part_number,
    organisation_id = p.organisation_id
FROM part p
WHERE p.id = a.part_id;

UPDATE part_attachment SET md5_hash = md5(data);

ALTER TABLE part_attachment ALTER COLUMN description     SET NOT NULL;
ALTER TABLE part_attachment ALTER COLUMN md5_hash        SET NOT NULL;
ALTER TABLE part_attachment ALTER COLUMN organisation_id SET NOT NULL;

ALTER TABLE part_attachment DROP COLUMN part_id;
ALTER TABLE part_attachment DROP COLUMN display_order;

-- Deliberately not unique: the imported catalogue already holds the same picture many times over,
-- and collapsing those is a separate decision. New uploads match against this index and reuse the
-- first row they find.
CREATE INDEX idx_part_attachment_hash ON part_attachment (organisation_id, md5_hash, type);

CREATE INDEX idx_part_attachment_link_part ON part_attachment_link (part_id);
CREATE INDEX idx_part_attachment_link_attachment ON part_attachment_link (attachment_id);
