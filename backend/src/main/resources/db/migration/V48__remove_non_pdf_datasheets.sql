-- Remove the stored "datasheets" that are not documents at all.
--
-- A vendor answers a moved or retired PDF with HTTP 200 and an HTML landing page, not a 404, and
-- the downloader used to store whatever came back. The development catalogue holds two of these:
-- 167-byte "301 Moved Permanently" interstitials sitting where a datasheet should be. They are
-- worse than a missing datasheet — the Documents card offers them as files, and the spec extractor
-- would try to read one. `PartAttachmentService.uploadFromUrl` has refused non-PDFs since the check
-- was added (`util/PdfBytes.looksLikePdf`); this clears what landed before that.
--
-- The condition mirrors that class exactly: a PDF is recognised by `%PDF` appearing within the
-- first 1024 bytes, not only at offset 0, since some servers prepend whitespace or a BOM. Scanning
-- 1028 bytes covers a header that starts at the last permitted offset.
--
-- Only DATASHEET rows are judged. An ATTACHMENT is whatever the user says it is — that is the whole
-- point of the type — and a PHOTO went through ImageIO before it was stored, so it is a real image.
-- `part.datasheet_url` is deliberately left alone: it is still the canonical link, and re-fetching
-- it is what the Documents card's "Download from URL" and the re-sourcing tool are for.

DO $$
DECLARE
    removed INTEGER;
BEGIN
    -- The link rows go with them through part_attachment_link's ON DELETE CASCADE.
    DELETE FROM part_attachment
    WHERE type = 'DATASHEET'
      AND position('\x25504446'::bytea IN substring(data FROM 1 FOR 1028)) = 0;
    GET DIAGNOSTICS removed = ROW_COUNT;
    RAISE NOTICE 'Removed % datasheet attachment(s) that were not PDFs', removed;
END $$;

-- Removing an attachment can leave a gap in a part's ordering, which is expected to stay 0-based
-- and contiguous per (part, type) — the same invariant PartAttachmentService.delete maintains.
WITH ordered AS (
    SELECT l.id,
           ROW_NUMBER() OVER (PARTITION BY l.part_id, a.type ORDER BY l.display_order, l.id) - 1 AS seq
    FROM part_attachment_link l
    JOIN part_attachment a ON a.id = l.attachment_id
)
UPDATE part_attachment_link l
SET display_order = o.seq
FROM ordered o
WHERE o.id = l.id AND l.display_order <> o.seq;
