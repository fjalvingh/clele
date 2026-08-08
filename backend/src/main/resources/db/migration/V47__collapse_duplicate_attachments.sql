-- Collapse the attachments that are byte-identical copies of each other.
--
-- V46 made attachments shareable and gave every row an MD5, which exposed how much of the existing
-- store is the same file over and over: measured on the development catalogue, 403 of 955 rows
-- (98 groups, ~42%) are duplicates — the Partsbox import downloaded the same product photo for
-- every part it appeared on, and the same failed-download HTML landed as many "datasheets".
--
-- Each group keeps its lowest id, which is also the earliest — so the surviving row's `description`
-- is the first part the content was ever used for, exactly what that column is supposed to say.
-- Every link to a discarded row is re-pointed at the survivor, so no part loses a photo or a
-- document. The discarded ids stop resolving, which is only visible as a stale browser cache
-- fetching /parts/{id}/attachments/{oldId} once.
--
-- Deliberately no temporary-table ON COMMIT clause: this must survive whether or not Flyway wraps
-- the migration in a single transaction. The tables are dropped explicitly at the end.

CREATE TEMPORARY TABLE attachment_merge AS
SELECT a.id AS dup_id, k.keep_id
FROM part_attachment a
JOIN (
    SELECT organisation_id, md5_hash, type, MIN(id) AS keep_id
    FROM part_attachment
    GROUP BY organisation_id, md5_hash, type
) k ON k.organisation_id = a.organisation_id
   AND k.md5_hash = a.md5_hash
   AND k.type = a.type
WHERE a.id <> k.keep_id;

-- Where a part links to more than one row of the same group — it held the same photo twice, under
-- two ids — re-pointing them all would collide with the unique (part_id, attachment_id) key. Work
-- out each link's destination first and keep only the earliest link per destination.
CREATE TEMPORARY TABLE link_target AS
SELECT l.id AS link_id, l.part_id, COALESCE(m.keep_id, l.attachment_id) AS target_id
FROM part_attachment_link l
LEFT JOIN attachment_merge m ON m.dup_id = l.attachment_id;

DELETE FROM part_attachment_link
WHERE id IN (
    SELECT link_id FROM (
        SELECT link_id,
               ROW_NUMBER() OVER (PARTITION BY part_id, target_id ORDER BY link_id) AS rn
        FROM link_target
    ) ranked
    WHERE rn > 1
);

UPDATE part_attachment_link l
SET attachment_id = m.keep_id
FROM attachment_merge m
WHERE l.attachment_id = m.dup_id;

DELETE FROM part_attachment a
USING attachment_merge m
WHERE a.id = m.dup_id;

-- Dropping the extra links can leave gaps, and display_order is expected to be 0-based and
-- contiguous within a part's attachments of one type (the service re-sequences it on delete).
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

DROP TABLE link_target;
DROP TABLE attachment_merge;
